const MAIN_REPO = "kyzzniko-lang/ifafu-kyzz";
const COMMENT_REPO = "kyzzniko-lang/ifafu-kyzz-comment";
const REVIEW_REPO = "kyzzniko-lang/ifafu-kyzz-course-review";
function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "access-control-allow-origin": "*",
      "access-control-allow-headers": "content-type",
      "access-control-allow-methods": "GET, POST, PUT, DELETE, OPTIONS",
    },
  });
}

function text(value, max) {
  return String(value ?? "").trim().slice(0, max);
}

async function readJson(request) {
  const length = Number(request.headers.get("content-length") || 0);
  if (length > 20_000) throw new Error("payload_too_large");
  const raw = await request.text();
  if (raw.length > 20_000) throw new Error("payload_too_large");
  return JSON.parse(raw);
}

async function github(env, repo, path, method = "GET", body) {
  const response = await fetch(`https://api.github.com/repos/${repo}/${path}`, {
    method,
    headers: {
      accept: "application/vnd.github+json",
      authorization: `Bearer ${env.GITHUB_TOKEN}`,
      "user-agent": "iFAFU-Feedback-Worker",
      "x-github-api-version": "2022-11-28",
      ...(body ? { "content-type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const raw = await response.text();
  let data = null;
  try { data = raw ? JSON.parse(raw) : null; } catch (_) {}
  return { response, data };
}

function commentData(item) {
  try { return JSON.parse(item.body || "{}"); } catch (_) { return {}; }
}

function publicComment(item) {
  const data = commentData(item);
  return {
    id: String(item.id),
    content: data.content || "",
    nickname: data.nickname || "",
    authorId: data.authorId || "",
    tag: data.tag || "",
    likes: Array.isArray(data.likes) ? data.likes : [],
    createdAt: item.created_at || "",
  };
}

async function findNickname(env, userId) {
  for (let page = 1; page <= 20; page++) {
    const result = await github(env, COMMENT_REPO, `issues/2/comments?per_page=100&page=${page}`);
    if (!result.response.ok || !Array.isArray(result.data) || result.data.length === 0) return null;
    for (const item of result.data) {
      const data = commentData(item);
      if (data.userId === userId) return item;
    }
    if (result.data.length < 100) return null;
  }
  return null;
}

async function deleteOwnedComment(env, repo, id, authorId) {
  const item = await github(env, repo, `issues/comments/${encodeURIComponent(id)}`);
  if (!item.response.ok) return json({ ok: false, message: "内容不存在" }, 404);
  if (commentData(item.data).authorId !== authorId) {
    return json({ ok: false, message: "无权删除此内容" }, 403);
  }
  const deleted = await github(env, repo, `issues/comments/${encodeURIComponent(id)}`, "DELETE");
  return deleted.response.ok ? json({ ok: true }) : json({ ok: false, message: "删除失败" }, 502);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204 });
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "ifafu-feedback" });
    }
    if (!env.GITHUB_TOKEN) return json({ ok: false, message: "服务尚未配置" }, 500);

    let input;
    try { input = await readJson(request); }
    catch (error) { return json({ ok: false, message: error.message === "payload_too_large" ? "反馈内容过长" : "请求格式错误" }, 400); }

    try {
      if (request.method === "POST" && url.pathname === "/issue") {
        const title = text(input.title, 120);
        const description = text(input.description, 10_000);
        if (title.length < 4 || description.length < 10) return json({ ok: false, message: "标题或描述过短" }, 400);
        const body = [
          "## 问题描述", "", description, "", "## 环境信息", "",
          `- 应用版本：${text(input.appVersion, 30) || "未知"}`,
          `- 设备信息：${text(input.deviceInfo, 300) || "未知"}`,
          `- 联系方式：${text(input.contact, 200) || "未提供"}`,
          "", "> 此 Issue 由 iFAFU 应用反馈功能自动创建。",
        ].join("\n");
        const result = await github(env, MAIN_REPO, "issues", "POST", { title: `[用户反馈] ${title}`, body, labels: ["用户反馈"] });
        if (!result.response.ok) return json({ ok: false, message: "提交失败，请稍后重试" }, 502);
        return json({ ok: true, issueNumber: result.data.number, issueUrl: result.data.html_url });
      }

      if (request.method === "POST" && url.pathname === "/crash") {
        const body = JSON.stringify({
          type: text(input.type, 30), time: text(input.time, 40), appVersion: text(input.appVersion, 30),
          device: text(input.device, 300), thread: text(input.thread, 100), message: text(input.message, 2_000),
          trace: text(input.trace, 8_000), description: text(input.description, 2_000),
        });
        const result = await github(env, COMMENT_REPO, "issues/3/comments", "POST", { body });
        return result.response.ok ? json({ ok: true }) : json({ ok: false, message: "提交失败" }, 502);
      }

      if (request.method === "POST" && url.pathname === "/comments") {
        const content = text(input.content, 2_000);
        const nickname = text(input.nickname, 80);
        const authorId = text(input.authorId, 128);
        if (content.length < 1 || !authorId) return json({ ok: false, message: "内容不完整" }, 400);
        const body = JSON.stringify({ nickname, content, authorId, tag: text(input.tag, 40), likes: [] });
        const result = await github(env, COMMENT_REPO, "issues/1/comments", "POST", { body });
        return result.response.ok ? json({ ok: true, data: result.data }) : json({ ok: false, message: "提交失败" }, 502);
      }

      const commentMatch = url.pathname.match(/^\/comments\/([^/]+)$/);
      if (request.method === "DELETE" && commentMatch) {
        return deleteOwnedComment(env, COMMENT_REPO, commentMatch[1], text(input.authorId, 128));
      }

      const likeMatch = url.pathname.match(/^\/comments\/([^/]+)\/like$/);
      if (request.method === "POST" && likeMatch) {
        const userId = text(input.userId, 128);
        const current = await github(env, COMMENT_REPO, `issues/comments/${encodeURIComponent(likeMatch[1])}`);
        if (!current.response.ok) return json({ ok: false, message: "内容不存在" }, 404);
        const data = commentData(current.data);
        const likes = Array.isArray(data.likes) ? data.likes : [];
        const index = likes.indexOf(userId);
        if (index >= 0) likes.splice(index, 1); else likes.push(userId);
        data.likes = likes;
        const updated = await github(env, COMMENT_REPO, `issues/comments/${encodeURIComponent(likeMatch[1])}`, "PATCH", { body: JSON.stringify(data) });
        return updated.response.ok ? json({ ok: true, data: publicComment({ ...current.data, body: JSON.stringify(data) }) }) : json({ ok: false, message: "操作失败" }, 502);
      }

      if (request.method === "PUT" && url.pathname === "/nicknames") {
        const userId = text(input.userId, 128);
        const nickname = text(input.nickname, 80);
        if (!userId || !nickname) return json({ ok: false, message: "昵称不能为空" }, 400);
        const existing = await findNickname(env, userId);
        const body = JSON.stringify({ userId, nickname });
        const result = existing
          ? await github(env, COMMENT_REPO, `issues/comments/${existing.id}`, "PATCH", { body })
          : await github(env, COMMENT_REPO, "issues/2/comments", "POST", { body });
        return result.response.ok ? json({ ok: true }) : json({ ok: false, message: "保存失败" }, 502);
      }

      if (request.method === "POST" && url.pathname === "/course-reviews") {
        const review = {
          courseName: text(input.courseName, 120), teacher: text(input.teacher, 80),
          difficulty: Math.min(5, Math.max(1, Number(input.difficulty) || 3)),
          grading: Math.min(5, Math.max(1, Number(input.grading) || 3)),
          attendance: Math.min(5, Math.max(1, Number(input.attendance) || 3)),
          comment: text(input.comment, 2_000), nickname: text(input.nickname, 80), authorId: text(input.authorId, 128),
        };
        if (!review.courseName || !review.authorId) return json({ ok: false, message: "评价信息不完整" }, 400);
        const result = await github(env, REVIEW_REPO, "issues/1/comments", "POST", { body: JSON.stringify(review) });
        return result.response.ok ? json({ ok: true }) : json({ ok: false, message: "提交失败" }, 502);
      }

      const reviewMatch = url.pathname.match(/^\/course-reviews\/([^/]+)$/);
      if (request.method === "DELETE" && reviewMatch) {
        return deleteOwnedComment(env, REVIEW_REPO, reviewMatch[1], text(input.authorId, 128));
      }

      return json({ ok: false, message: "Not found" }, 404);
    } catch (error) {
      console.error("Worker request failed", error?.message || "unknown");
      return json({ ok: false, message: "服务异常，请稍后重试" }, 500);
    }
  },
};
