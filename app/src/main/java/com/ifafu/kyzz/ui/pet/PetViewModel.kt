package com.ifafu.kyzz.ui.pet

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ifafu.kyzz.data.model.Pet
import com.ifafu.kyzz.data.model.PetState
import com.ifafu.kyzz.data.repository.PetRepository
import com.ifafu.kyzz.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class PetViewModel @Inject constructor(
    private val petRepository: PetRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _pet = MutableLiveData<Pet>()
    val pet: LiveData<Pet> = _pet

    private val _bubbleText = MutableLiveData<String?>()
    val bubbleText: LiveData<String?> = _bubbleText

    private val _showLevelUp = MutableLiveData<Int?>()
    val showLevelUp: LiveData<Int?> = _showLevelUp

    private val _checkInResult = MutableLiveData<CheckInResult?>()
    val checkInResult: LiveData<CheckInResult?> = _checkInResult

    private var lastGradeAdvice: String? = null
    private var lastBubbleTime = 0L

    data class CheckInResult(val points: Int, val streak: Int, val alreadyChecked: Boolean)

    init {
        _pet.value = petRepository.loadPet()
        startAutoUpdate()
    }

    private fun startAutoUpdate() {
        viewModelScope.launch {
            while (true) {
                delay(60_000) // 每分钟更新一次
                updateState()
            }
        }
    }

    fun updateState() {
        val pet = _pet.value ?: return
        pet.tick()
        updatePetState(pet)
        _pet.value = pet
        petRepository.savePet(pet)
    }

    fun reloadPet() {
        _pet.value = petRepository.loadPet()
    }

    private fun updatePetState(pet: Pet) {
        // Don't overwrite interactive states (EATING/HAPPY from play) if recent
        val timeSinceInteract = System.currentTimeMillis() - pet.lastInteractTime
        if (timeSinceInteract < 15_000 && (pet.state == PetState.EATING || pet.state == PetState.HAPPY || pet.state == PetState.EXCITED)) {
            return
        }

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val minute = Calendar.getInstance().get(Calendar.MINUTE)
        val nowMinutes = hour * 60 + minute

        // 根据时间自动切换状态
        when {
            hour in 0..5 -> {
                pet.state = PetState.SLEEPING
            }
            hour in 6..7 -> {
                pet.state = PetState.IDLE
            }
            // 上课时间段
            nowMinutes in 480..765 || nowMinutes in 840..1060 -> {
                pet.state = PetState.STUDYING
            }
            // 晚自习
            nowMinutes in 1140..1295 -> {
                pet.state = PetState.STUDYING
            }
            pet.hunger < 30 -> {
                pet.state = PetState.HUNGRY
            }
            pet.mood < 30 -> {
                pet.state = PetState.SAD
            }
            pet.mood > 80 -> {
                pet.state = PetState.HAPPY
            }
            else -> {
                pet.state = PetState.IDLE
            }
        }
    }

    fun onPetClicked() {
        val pet = _pet.value ?: return
        pet.lastInteractTime = System.currentTimeMillis()

        // 增加心情
        pet.mood = (pet.mood + 3).coerceAtMost(100)
        val leveled = pet.addExp(2)

        // 随机对话
        val dialogue = getRandomDialogue(pet)
        _bubbleText.value = dialogue

        if (leveled) {
            _showLevelUp.value = pet.level
        }

        _pet.value = pet
        petRepository.savePet(pet)

        // 3秒后隐藏气泡
        viewModelScope.launch {
            delay(3000)
            _bubbleText.value = null
        }
    }

    private fun getRandomDialogue(pet: Pet): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hungerDialogues = listOf(
            "肚子好饿...能喂我吃点东西吗？",
            "咕咕咕~ 我想吃小鱼干！",
            "主人~ 我快饿扁了喵..."
        )
        val moodDialogues = listOf(
            "今天心情不太好...",
            "有点不开心，能陪我玩吗？",
            "呜呜~ 我需要安慰..."
        )
        val studyDialogues = listOf(
            "好好学习，天天向上！",
            "今天的课上完了吗？",
            "学习使我快乐喵~",
            "加油！考试快到了！",
            "努力学习才能变强！"
        )
        val idleDialogues = listOf(
            "喵~ 今天天气不错呢",
            "主人好！摸摸我嘛~",
            "嘿嘿，我最喜欢你了！",
            "呼噜呼噜~ 好舒服",
            "你在干什么呀？",
            "喵呜~ 想出去玩",
            "我帮你看着课表呢！"
        )
        val nightDialogues = listOf(
            "该睡觉了喵~ 晚安",
            "别熬夜了，早点休息！",
            "呼... 好困... zzz",
            "明天还有课呢，快睡吧"
        )
        val happyDialogues = listOf(
            "今天超开心的！喵哈哈~",
            "心情好好呀！",
            "和主人在一起最幸福了！",
            "嘿嘿嘿~ 我是世界上最幸福的猫！"
        )
        val examDialogues = listOf(
            "考试加油！我相信你！",
            "好好复习，不要紧张~",
            "你可以的！冲冲冲！"
        )

        return when {
            pet.state == PetState.HUNGRY -> hungerDialogues.random()
            pet.state == PetState.SAD -> moodDialogues.random()
            pet.state == PetState.SLEEPING || hour >= 23 -> nightDialogues.random()
            pet.state == PetState.HAPPY -> happyDialogues.random()
            pet.state == PetState.STUDYING -> studyDialogues.random()
            pet.state == PetState.EXAM_REMIND -> examDialogues.random()
            else -> idleDialogues.random()
        }
    }

    fun feed() {
        val pet = _pet.value ?: return
        if (!pet.canFeed()) {
            val mins = (pet.nextFeedRecoverIn() / 60_000).toInt()
            _bubbleText.value = "肚子还饱着呢~ ${mins}分钟后再喂我吧"
            viewModelScope.launch { delay(2000); _bubbleText.value = null }
            return
        }
        pet.feed()
        pet.lastInteractTime = System.currentTimeMillis()
        pet.state = PetState.EATING
        _bubbleText.value = getFeedResponse()
        _pet.value = pet
        petRepository.savePet(pet)
        viewModelScope.launch {
            delay(3000)
            _bubbleText.value = null
            // 吃完后恢复状态
            val current = _pet.value ?: return@launch
            updatePetState(current)
            _pet.value = current
            petRepository.savePet(current)
        }
    }

    private fun getFeedResponse(): String = listOf(
        "好好吃！谢谢主人~",
        "喵~ 真好吃！",
        "吃饱了，好满足！",
        "这个味道不错喵~",
        "还要还要！嘿嘿~",
        "就这？我吃的比你的绩点还少！",
        "还行吧，比食堂好吃一点点~",
        "你喂我的时候笑得那么开心，是不是有求于我？",
        "看在你喂我的份上，暂时不吐槽你了~",
        "不错不错，以后继续保持！",
        "吃饱了才有力气监督你学习！",
        "这就是传说中的投喂吗？我还要！"
    ).random()

    fun play() {
        val pet = _pet.value ?: return
        if (!pet.canPlay()) {
            val mins = (pet.nextPlayRecoverIn() / 60_000).toInt()
            _bubbleText.value = "我有点累了~ ${mins}分钟后再陪我玩吧"
            viewModelScope.launch { delay(2000); _bubbleText.value = null }
            return
        }
        pet.play()
        pet.lastInteractTime = System.currentTimeMillis()
        pet.state = PetState.HAPPY
        _bubbleText.value = getPlayResponse()
        _pet.value = pet
        petRepository.savePet(pet)
        viewModelScope.launch {
            delay(3000)
            _bubbleText.value = null
            // 玩完后恢复状态
            val current = _pet.value ?: return@launch
            updatePetState(current)
            _pet.value = current
            petRepository.savePet(current)
        }
    }

    private fun getPlayResponse(): String = listOf(
        "好好玩！再来一次！",
        "喵哈哈~ 好开心！",
        "和主人玩最开心了！",
        "嘿嘿~ 抓到你了！",
        "再玩再玩！",
        "你作业写完了吗就来玩？算了，我也是~",
        "这就是传说中的劳逸结合吗？逸得有点多啊~",
        "玩归玩，别忘了待会去学习！",
        "你确定不先把代码写了？那好吧~",
        "摸鱼一时爽，一直摸鱼一直爽！",
        "我怀疑你只是想找个借口不学习~"
    ).random()

    fun onGradeChanged(isImproved: Boolean, courseName: String) {
        val pet = _pet.value ?: return
        val advice = if (isImproved) {
            pet.mood = (pet.mood + 15).coerceAtMost(100)
            pet.state = PetState.EXCITED
            listOf(
                "${courseName}进步了！太棒了喵~",
                "成绩提高了！主人好厉害！",
                "${courseName}考得不错！继续保持！",
                "${courseName}进步了？是不是抄的？开玩笑的啦~",
                "厉害了！是不是终于开始学习了？",
                "${courseName}不错不错，看来我督促有效果！"
            ).random()
        } else {
            pet.mood = (pet.mood - 10).coerceAtLeast(0)
            pet.state = PetState.WORRY
            listOf(
                "${courseName}退步了... 要加油哦！",
                "别灰心，下次一定能考好！",
                "${courseName}需要多花时间复习呢~",
                "我陪你一起努力！不要放弃！",
                "${courseName}退步了？是不是天天摸我耽误学习了？",
                "你的绩点在滴血，你听到了吗？",
                "我就说让你少摸我多看书吧！",
                "没关系，至少你还有我...虽然我也有点嫌弃你了~"
            ).random()
        }
        lastGradeAdvice = advice
        _bubbleText.value = advice
        _pet.value = pet
        petRepository.savePet(pet)
        viewModelScope.launch { delay(5000); _bubbleText.value = null }
    }

    fun onExamComing(courseName: String, daysLeft: Int) {
        val pet = _pet.value ?: return
        pet.state = PetState.EXAM_REMIND
        val msg = when {
            daysLeft == 0 -> listOf(
                "今天考${courseName}！加油喵！",
                "今天考${courseName}？你复习了吗？没有？那祝你好运！",
                "${courseName}今天考！相信自己...虽然我不太信~"
            ).random()
            daysLeft == 1 -> listOf(
                "明天就考${courseName}了，准备好了吗？",
                "明天考${courseName}！今晚通宵预习还来得及！",
                "明天就考了？你确定不现在开始看书？"
            ).random()
            daysLeft <= 3 -> listOf(
                "还有${daysLeft}天就考${courseName}了，该复习了！",
                "还有${daysLeft}天考${courseName}，现在复习还来得及，再晚就真来不及了！",
                "${courseName}还有${daysLeft}天，你的复习计划呢？什么？没有计划？"
            ).random()
            daysLeft <= 7 -> listOf(
                "${courseName}考试临近，开始复习吧~",
                "下周就考${courseName}了，要不要我帮你划重点？开玩笑的，我又不会~",
                "${courseName}快考了，你是不是该减少摸我的时间了？"
            ).random()
            else -> return
        }
        _bubbleText.value = msg
        _pet.value = pet
        petRepository.savePet(pet)
        viewModelScope.launch { delay(5000); _bubbleText.value = null }
    }

    fun dismissLevelUp() {
        _showLevelUp.value = null
    }

    fun getLastGradeAdvice(): String? = lastGradeAdvice

    fun checkIn() {
        val pet = _pet.value ?: return
        if (pet.isCheckedInToday()) {
            _checkInResult.value = CheckInResult(0, pet.checkInStreak, true)
            return
        }
        val oldPoints = pet.points
        pet.checkIn()
        _checkInResult.value = CheckInResult(pet.points - oldPoints, pet.checkInStreak, false)
        _pet.value = pet
        petRepository.savePet(pet)
    }

    fun dismissCheckInResult() {
        _checkInResult.value = null
    }

    fun triggerRandomBubble(todayCourseCount: Int, nextExam: Pair<String, Int>?, countdownEvents: List<Pair<String, Int>> = emptyList()) {
        val pet = _pet.value ?: return
        val now = System.currentTimeMillis()
        if (now - lastBubbleTime < 30_000) return // 30秒内不重复冒泡

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val messages = mutableListOf<String>()
        when {
            pet.state == PetState.HUNGRY -> messages.add("肚子好饿...能喂我吃点东西吗？")
            pet.state == PetState.SLEEPING -> messages.add("zzz...呼噜...")
            hour in 0..5 -> messages.addAll(listOf(
                "该睡觉了喵~", "别熬夜了！", "明天还有课呢~",
                "你不要命啦？快去睡觉！",
                "猝死新闻看少了是吧？",
                "你是要修仙吗？明天的课怎么办！"
            ))
            todayCourseCount > 0 && hour < 12 -> messages.addAll(listOf(
                "今天有${todayCourseCount}节课哦~",
                "还有${todayCourseCount}节课，惊不惊喜？",
                "${todayCourseCount}节课在等你，快起床！"
            ))
            todayCourseCount > 0 && hour >= 12 -> messages.addAll(listOf(
                "今天还剩课要上呢~",
                "课还没上完呢，别摆烂！",
                "下午的课在向你微笑~"
            ))
            todayCourseCount == 0 && hour in 8..17 -> messages.addAll(listOf(
                "今天没课，好好休息喵~",
                "今天没课？那更要好好学（玩）习（耍）！",
                "难得没课，别全睡过去了！"
            ))
        }
        if (nextExam != null && nextExam.second in 0..7) {
            messages.add("${nextExam.first}${if (nextExam.second == 0) "今天考！" else "${nextExam.second}天后考"}，准备好了吗？")
        }
        for ((name, days) in countdownEvents) {
            if (days in 0..7) {
                messages.add("距离${name}还有${days}天了，加油！")
            }
        }
        if (messages.isEmpty()) {
            messages.addAll(listOf(
                "喵~", "摸摸我嘛~", "你在干什么呀？", "呼噜呼噜~",
                "又在玩手机？看看书吧！",
                "你的绩点在哭泣哦~",
                "今天背单词了吗？没有？那摸我干嘛！",
                "我虽然是虚拟的，但你的期末是真实的！",
                "别摸我了，去写作业！",
                "你再不学习，我就要替你去考试了！",
                "我在替你的未来担忧喵~",
                "摸鱼可以，但别忘了正事！",
                "你是不是忘了什么？对，就是学习！",
                "图书馆在向你招手哦~",
                "听说挂科补考很痛苦的，我只是听说~",
                "你已经盯着屏幕很久了，眼睛不酸吗？",
                "我比你的绩点可爱多了对吧？",
                "距离下次考试还有...算了不说了，怕你哭~",
                "铲屎的，今天努力了吗？",
                "你以为养宠物就不用学习了？",
                "我的快乐建立在你好好学习之上！",
                "再不努力，毕业就要进厂拧螺丝了喵~"
            ))
        }

        lastBubbleTime = now
        _bubbleText.value = messages.random()
        viewModelScope.launch {
            delay(4000)
            _bubbleText.value = null
        }
    }

    fun getChatGreeting(todayCourseCount: Int, nextExamName: String?): String {
        val pet = _pet.value ?: return "喵~"
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreet = when {
            hour < 6 -> "这么晚还没睡呀"
            hour < 12 -> "早上好"
            hour < 18 -> "下午好"
            else -> "晚上好"
        }
        val stateGreet = when (pet.state) {
            PetState.HUNGRY -> listOf("我有点饿了喵...", "你要饿死我吗？！", "再不喂我我就翻桌了！").random()
            PetState.SLEEPING -> listOf("好困...但你来了我就醒啦！", "嗯...你来干嘛...人家在睡觉...", "打扰我睡觉是要付出代价的！").random()
            PetState.HAPPY -> listOf("见到你超开心的！", "今天心情不错，允许你摸一下！", "你来啦！我刚把你的作业藏好了~").random()
            PetState.SAD -> listOf("不太开心...陪我聊会吧", "哼！你终于想起我了？", "你知道我等了你多久吗？").random()
            PetState.STUDYING -> listOf("学习中！一起加油！", "别打扰我，我在替你学习！", "你学你的，我学我的，咱们各凭本事！").random()
            PetState.EXAM_REMIND -> listOf("考试要来了！一起冲刺！", "完了完了要考试了！什么？是你考不是我？那没事了~", "距离挂科还有...啊不，距离考试还有几天！").random()
            else -> listOf("嘿嘿~", "哟，来了？", "今天又来摸鱼了？").random()
        }
        val courseInfo = if (todayCourseCount > 0) "今天有${todayCourseCount}节课，" else ""
        val examInfo = if (nextExamName != null) "${nextExamName}快考了，" else ""
        return "${timeGreet}${pet.name}！${stateGreet} ${courseInfo}${examInfo}有什么想聊的吗？"
    }

    fun onExamProgressUpdate(examName: String, newStatus: Int) {
        val pet = _pet.value ?: return
        when (newStatus) {
            1 -> {
                pet.mood = (pet.mood + 3).coerceAtMost(100)
                _bubbleText.value = "${examName}开始复习了，加油喵~"
            }
            2 -> {
                pet.mood = (pet.mood + 10).coerceAtMost(100)
                pet.state = PetState.HAPPY
                _bubbleText.value = "太棒了，${examName}搞定了！"
            }
        }
        _pet.value = pet
        petRepository.savePet(pet)
        viewModelScope.launch { delay(3000); _bubbleText.value = null }
    }
}
