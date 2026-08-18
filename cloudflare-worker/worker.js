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

function homePage() {
  const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>iFAFU · 校园助手</title><style>
  :root{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}*{box-sizing:border-box}body{margin:0;min-height:100vh;background:#f8f7f4;color:#242321}main{width:min(720px,calc(100% - 32px));margin:auto;padding:72px 0 56px}.brand{letter-spacing:.22em;color:#9b958d;font-size:13px;margin-bottom:58px}h1{margin:0 0 14px;font-family:Georgia,"Songti SC",serif;font-size:clamp(42px,8vw,72px);font-weight:400;letter-spacing:-.04em}.lead{margin:0 0 46px;color:#77716a;font-size:18px;line-height:1.8}.card{background:#fff;border:1px solid #e8e4df;border-radius:18px;padding:28px;box-shadow:0 8px 30px rgba(48,40,30,.05)}h2{margin:0 0 22px;font-size:21px;font-weight:600}label{display:block;margin:16px 0 8px;color:#6e6861;font-size:14px}input,textarea{width:100%;border:1px solid #ded9d2;border-radius:10px;padding:13px 14px;color:#282623;background:#fff;font:inherit;outline:none}input:focus,textarea:focus{border-color:#d9774a;box-shadow:0 0 0 3px rgba(217,119,74,.12)}textarea{min-height:150px;resize:vertical}button{margin-top:20px;border:0;border-radius:10px;padding:13px 22px;background:#d9774a;color:#fff;font:inherit;cursor:pointer}button:disabled{opacity:.55;cursor:wait}#status{min-height:24px;margin:14px 0 0;color:#77716a;font-size:14px}footer{margin-top:26px;color:#9b958d;font-size:13px}
  </style></head><body><main><div class="brand">iFAFU</div><h1>校园助手</h1><p class="lead">福农校园服务与反馈入口</p><section class="card"><h2>提交意见与问题</h2><form id="feedback"><label for="title">主题</label><input id="title" maxlength="120" placeholder="例如：成绩刷新较慢" required><label for="description">详细描述</label><textarea id="description" maxlength="10000" placeholder="请描述遇到的问题或建议（至少 10 个字）" required></textarea><button id="submit" type="submit">提交反馈</button><p id="status" role="status"></p></form></section><footer>感谢你的反馈，我们会持续改进 iFAFU。</footer></main><script>
  const form=document.getElementById('feedback'),button=document.getElementById('submit'),status=document.getElementById('status');form.addEventListener('submit',async(e)=>{e.preventDefault();const title=document.getElementById('title').value.trim(),description=document.getElementById('description').value.trim();if(title.length<4||description.length<10){status.textContent='请填写完整内容后再提交。';return}button.disabled=true;status.textContent='正在提交…';try{const r=await fetch('/issue',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({title,description,contact:'网页提交'})}),d=await r.json();if(!r.ok||!d.ok)throw new Error(d.message||'提交失败');status.textContent='提交成功，感谢你的反馈。';form.reset()}catch(err){status.textContent=err.message||'网络异常，请稍后重试。'}finally{button.disabled=false}});
  </script></body></html>`;
  return new Response(html,{headers:{"content-type":"text/html; charset=utf-8","cache-control":"public, max-age=300"}});
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204 });
    if (request.method === "GET" && url.pathname === "/") return homePage();
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "ifafu-feedback" });
    }
    if (!env.GITHUB_TOKEN) return json({ ok: false, message: "服务尚未配置" }, 500);

    // GET 请求没有 JSON body，必须在 readJson 之前处理，否则会被误判为请求格式错误。
    if (request.method === "GET" && url.pathname === "/comments") {
      const perPage = Math.min(100, Math.max(1, Number(url.searchParams.get("per_page") || 20)));
      const page = Math.max(1, Number(url.searchParams.get("page") || 1));
      const result = await github(
        env,
        COMMENT_REPO,
        `issues/1/comments?per_page=${perPage}&page=${page}&sort=created&direction=desc`
      );
      if (!result.response.ok || !Array.isArray(result.data)) {
        return json({ ok: false, message: "评论暂时无法加载" }, 502);
      }
      return json({ ok: true, data: result.data.map(publicComment) });
    }

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
