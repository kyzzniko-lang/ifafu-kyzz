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
        pet.addExp(2)

        // 随机对话
        val dialogue = getRandomDialogue(pet)
        _bubbleText.value = dialogue

        val leveled = pet.addExp(0) // check if leveled
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
            _bubbleText.value = "今天已经吃了${Pet.MAX_DAILY_FEED}次了，明天再来喂我吧~"
            viewModelScope.launch { delay(2000); _bubbleText.value = null }
            return
        }
        pet.feed()
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
        "还要还要！嘿嘿~"
    ).random()

    fun play() {
        val pet = _pet.value ?: return
        if (!pet.canPlay()) {
            _bubbleText.value = "今天已经玩了${Pet.MAX_DAILY_PLAY}次了，明天再来陪我吧~"
            viewModelScope.launch { delay(2000); _bubbleText.value = null }
            return
        }
        pet.play()
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
        "再玩再玩！"
    ).random()

    fun onGradeChanged(isImproved: Boolean, courseName: String) {
        val pet = _pet.value ?: return
        val advice = if (isImproved) {
            pet.mood = (pet.mood + 15).coerceAtMost(100)
            pet.state = PetState.EXCITED
            listOf(
                "${courseName}进步了！太棒了喵~",
                "成绩提高了！主人好厉害！",
                "${courseName}考得不错！继续保持！"
            ).random()
        } else {
            pet.mood = (pet.mood - 10).coerceAtLeast(0)
            pet.state = PetState.WORRY
            listOf(
                "${courseName}退步了... 要加油哦！",
                "别灰心，下次一定能考好！",
                "${courseName}需要多花时间复习呢~",
                "我陪你一起努力！不要放弃！"
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
            daysLeft == 0 -> "今天考${courseName}！加油喵！"
            daysLeft == 1 -> "明天就考${courseName}了，准备好了吗？"
            daysLeft <= 3 -> "还有${daysLeft}天就考${courseName}了，该复习了！"
            daysLeft <= 7 -> "${courseName}考试临近，开始复习吧~"
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

    fun triggerRandomBubble(todayCourseCount: Int, nextExam: Pair<String, Int>?) {
        val pet = _pet.value ?: return
        val now = System.currentTimeMillis()
        if (now - lastBubbleTime < 30_000) return // 30秒内不重复冒泡

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val messages = mutableListOf<String>()
        when {
            pet.state == PetState.HUNGRY -> messages.add("肚子好饿...能喂我吃点东西吗？")
            pet.state == PetState.SLEEPING -> messages.add("zzz...呼噜...")
            hour in 0..5 -> messages.addAll(listOf("该睡觉了喵~", "别熬夜了！", "明天还有课呢~"))
            todayCourseCount > 0 && hour < 12 -> messages.add("今天有${todayCourseCount}节课哦~")
            todayCourseCount > 0 && hour >= 12 -> messages.add("今天还剩课要上呢~")
            todayCourseCount == 0 && hour in 8..17 -> messages.add("今天没课，好好休息喵~")
        }
        if (nextExam != null && nextExam.second in 0..7) {
            messages.add("${nextExam.first}${if (nextExam.second == 0) "今天考！" else "${nextExam.second}天后考"}，准备好了吗？")
        }
        if (messages.isEmpty()) {
            messages.addAll(listOf("喵~", "摸摸我嘛~", "你在干什么呀？", "呼噜呼噜~"))
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
            PetState.HUNGRY -> "我有点饿了喵..."
            PetState.SLEEPING -> "好困...但你来了我就醒啦！"
            PetState.HAPPY -> "见到你超开心的！"
            PetState.SAD -> "不太开心...陪我聊会吧"
            PetState.STUDYING -> "学习中！一起加油！"
            PetState.EXAM_REMIND -> "考试要来了！一起冲刺！"
            else -> "嘿嘿~"
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
