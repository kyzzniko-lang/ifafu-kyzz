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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val petMutex = Mutex()

    data class CheckInResult(val points: Int, val streak: Int, val alreadyChecked: Boolean)

    init {
        viewModelScope.launch {
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                petRepository.loadPet()
            }
            _pet.value = loaded
        }
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
        viewModelScope.launch {
            petMutex.withLock {
                // 先从磁盘读取最新数据，避免覆盖其他组件的修改
                val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    petRepository.loadPet()
                }
                fresh.tick()
                updatePetState(fresh)
                _pet.value = fresh
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    petRepository.savePet(fresh)
                }
            }
        }
    }

    fun reloadPet() {
        viewModelScope.launch {
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                petRepository.loadPet()
            }
            _pet.value = loaded
        }
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
        viewModelScope.launch {
            petMutex.withLock {
                val pet = _pet.value ?: return@withLock
                pet.lastInteractTime = System.currentTimeMillis()
                pet.mood = (pet.mood + 3).coerceAtMost(100)
                val leveled = pet.addExp(2)
                val dialogue = getRandomDialogue(pet)
                _bubbleText.value = dialogue
                if (leveled) { _showLevelUp.value = pet.level }
                _pet.value = pet
                withContext(Dispatchers.IO) { petRepository.savePet(pet) }
            }
            // 3秒后隐藏气泡（在 Mutex 外，不阻塞其他交互）
            delay(3000)
            _bubbleText.value = null
        }
    }

    private fun getRandomDialogue(pet: Pet): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isCrab = pet.petType == "crab"
        val isCloudling = pet.petType == "cloudling"

        val hungerDialogues = when {
            isCrab -> listOf(
                "代码跑不动了...能量不足，喂点东西？",
                "我的钳子都没力气敲键盘了...",
                "主人，再不喂我我就要debug你了",
                "你是不是忘了我还没吃饭？你自己的绩点忘了就算了，我也能忘？",
                "饿到编译不过了...快投喂！",
                "我都快饿成注释了——被删了都没人在意的那种"
            )
            isCloudling -> listOf(
                "我的光芒变暗了...需要补充能量！",
                "云朵快散了...喂点东西吧！",
                "饿到飘不起来了...",
                "你再不喂我，我就蒸发给你看！",
                "主人，你是不是把我忘了？和你忘交作业一样？",
                "我都快饿成空指针了——随时崩溃"
            )
            else -> listOf(
                "肚子好饿...能喂我吃点东西吗？",
                "咕咕咕~ 我想吃小鱼干！",
                "主人~ 我快饿扁了喵...",
                "你再不喂我，我就绝食！...好吧我做不到，但我会生气！",
                "饿到连卖萌的力气都没了...你忍心吗？",
                "主人你是不是只顾着刷手机？我都饿瘦了喵！"
            )
        }
        val moodDialogues = when {
            isCrab -> listOf(
                "今天的commit质量不太行...",
                "有点emo，能陪我写会儿代码吗？",
                "我的bug还没修完...",
                "你是不是又在外面摸鱼不带我？",
                "心情差是因为你的代码太烂了，看着心烦",
                "哼，你是不是对别的宠物好了？"
            )
            isCloudling -> listOf(
                "今天心情有点阴天...",
                "有点不开心，能陪我聊会吗？",
                "呜呜~ 我需要安慰...",
                "你是不是又在外面摸鱼不带我？",
                "你今天都没理我...我又不是空气",
                "哼，你是不是对别的宠物好了？"
            )
            else -> listOf(
                "今天心情不太好...",
                "有点不开心，能陪我玩吗？",
                "呜呜~ 我需要安慰...",
                "你是不是又在外面摸鱼不带我？",
                "你今天都没理我...我又不是空气喵！",
                "哼，你是不是对别的宠物好了？"
            )
        }
        val studyDialogues = when {
            isCrab -> listOf(
                "好好学习，代码才能跑得更快！",
                "今天的课上完了吗？",
                "学习使我编译通过！",
                "加油！考试快到了！",
                "努力学习才能写出优雅的代码！",
                "你是不是又在摸鱼？别以为我不知道！",
                "你看看你那个绩点，好意思不学习？",
                "学不学随你，反正挂的不是我"
            )
            isCloudling -> listOf(
                "好好学习，天天向上！",
                "今天的课上完了吗？",
                "学习使我更闪亮！",
                "加油！考试快到了！",
                "努力学习才能变强！",
                "你是不是又在摸鱼？别以为我不知道！",
                "你看看你那个绩点，好意思不学习？",
                "学不学随你，反正挂的不是我"
            )
            else -> listOf(
                "好好学习，天天向上！",
                "今天的课上完了吗？",
                "学习使我快乐喵~",
                "加油！考试快到了！",
                "努力学习才能变强！",
                "你是不是又在摸鱼？别以为我不知道喵！",
                "你看看你那个绩点，好意思不学习？",
                "学不学随你，反正挂的不是我~"
            )
        }
        val idleDialogues = when {
            isCrab -> listOf(
                "今天天气不错，适合写代码呢",
                "主人好！在忙什么项目？",
                "嘿嘿，我最喜欢和主人一起coding了！",
                "在思考下一行该写什么...",
                "你在干什么呀？",
                "想出去走走，看看有没有新bug",
                "我帮你看着课表呢！",
                "你又在刷手机？我都替你的绩点着急",
                "别摸我了，去学习！...好吧再摸一下",
                "你看你，又闲着？隔壁的宠物主人都在学习了"
            )
            isCloudling -> listOf(
                "今天天气不错呢~",
                "主人好！在忙什么呀？",
                "嘿嘿，和主人在一起最开心了！",
                "飘呀飘~ 好舒服",
                "你在干什么呀？",
                "想出去飘一飘~",
                "我帮你看着课表呢！",
                "你又在刷手机？我都替你的绩点着急",
                "别摸我了，去学习！...好吧再摸一下",
                "你看你，又闲着？隔壁的宠物主人都在学习了"
            )
            else -> listOf(
                "喵~ 今天天气不错呢",
                "主人好！摸摸我嘛~",
                "嘿嘿，我最喜欢你了！",
                "呼噜呼噜~ 好舒服",
                "你在干什么呀？",
                "喵呜~ 想出去玩",
                "我帮你看着课表呢！",
                "你又在刷手机？我都替你的绩点着急喵！",
                "别摸我了，去学习！...好吧再摸一下~",
                "你看你，又闲着？隔壁的宠物主人都在学习了"
            )
        }
        val nightDialogues = when {
            isCrab -> listOf(
                "该睡觉了，晚安主人！",
                "别熬夜了，代码明天再跑！",
                "呼... 眼皮在打架... zzz",
                "明天还有课呢，快睡吧",
                "都几点了还不睡？你以为你是铁打的？",
                "熬夜变秃了可别怪我没提醒你",
                "你再不睡我就自己关机了！"
            )
            isCloudling -> listOf(
                "该睡觉了，晚安主人！",
                "别熬夜了，早点休息！",
                "呼... 好困... zzz",
                "明天还有课呢，快睡吧",
                "都几点了还不睡？你以为你是铁打的？",
                "熬夜变丑了可别怪我没提醒你",
                "你再不睡我就自己消散了！"
            )
            else -> listOf(
                "该睡觉了喵~ 晚安",
                "别熬夜了，早点休息！",
                "呼... 好困... zzz",
                "明天还有课呢，快睡吧",
                "都几点了还不睡？你以为你是铁打的喵？",
                "熬夜变秃了可别怪我没提醒你~",
                "你再不睡我就自己关机了喵！"
            )
        }
        val happyDialogues = when {
            isCrab -> listOf(
                "今天代码一次编译通过！超开心！",
                "心情好好呀！",
                "和主人在一起最幸福了！",
                "嘿嘿嘿~ 今天的代码写得真漂亮！",
                "难得你表现不错，奖励你多摸我两下~",
                "看你这么开心，是不是终于及格了？"
            )
            isCloudling -> listOf(
                "今天心情超好！闪闪发光！",
                "心情好好呀！",
                "和主人在一起最幸福了！",
                "嘿嘿嘿~ 今天是晴天！",
                "难得你表现不错，奖励你多摸我两下~",
                "看你这么开心，是不是终于及格了？"
            )
            else -> listOf(
                "今天超开心的！喵哈哈~",
                "心情好好呀！",
                "和主人在一起最幸福了！",
                "嘿嘿嘿~ 我是世界上最幸福的猫！",
                "难得你表现不错，奖励你多摸我两下喵~",
                "看你这么开心，是不是终于及格了？"
            )
        }
        val examDialogues = when {
            isCrab -> listOf(
                "考试加油！我相信你！",
                "好好复习，别紧张，就当在debug！",
                "你可以的！冲冲冲！",
                "还在这跟我玩？书翻了吗？",
                "考试要是挂了别来找我哭！"
            )
            isCloudling -> listOf(
                "考试加油！我相信你！",
                "好好复习，不要紧张~",
                "你可以的！冲冲冲！",
                "还在这跟我玩？书翻了吗？",
                "考试要是挂了别来找我哭！"
            )
            else -> listOf(
                "考试加油！我相信你！",
                "好好复习，不要紧张~",
                "你可以的！冲冲冲！",
                "还在这跟我玩？书翻了吗喵？",
                "考试要是挂了别来找我哭！"
            )
        }

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
        viewModelScope.launch {
            var fed = false
            petMutex.withLock {
                val pet = _pet.value ?: return@withLock
                if (!pet.canFeed()) {
                    val mins = (pet.nextFeedRecoverIn() / 60_000).toInt()
                    _bubbleText.value = "肚子还饱着呢~ ${mins}分钟后再喂我吧，你以为我是饭桶吗？"
                    delay(2000)
                    _bubbleText.value = null
                    return@withLock
                }
                pet.feed()
                pet.lastInteractTime = System.currentTimeMillis()
                pet.state = PetState.EATING
                _bubbleText.value = getFeedResponse()
                _pet.value = pet
                withContext(Dispatchers.IO) { petRepository.savePet(pet) }
                fed = true
            }
            if (!fed) return@launch
            delay(3000)
            _bubbleText.value = null
            petMutex.withLock {
                val current = _pet.value ?: return@withLock
                updatePetState(current)
                _pet.value = current
                withContext(Dispatchers.IO) { petRepository.savePet(current) }
            }
        }
    }

    private fun getFeedResponse(): String {
        val pet = _pet.value
        val isCrab = pet?.petType == "crab"
        val isCloudling = pet?.petType == "cloudling"
        return when {
            isCrab -> listOf(
                "能量补充完毕！继续coding！",
                "不错不错，这波投喂可以！",
                "吃饱了才有力气敲键盘！",
                "这味道...比Stack Overflow还让人满足！",
                "还要还要！嘿嘿~",
                "就这？我吃的比你的绩点还少！",
                "还行吧，比食堂好吃一点点~",
                "你喂我的时候笑得那么开心，是不是有求于我？",
                "看在你喂我的份上，暂时不吐槽你了~",
                "不错不错，以后继续保持！",
                "吃饱了才有力气监督你学习！",
                "这就是传说中的投喂吗？我还要！",
                "总算想起来喂我了？我还以为你把我忘了呢",
                "吃人嘴软...但我还是要说，你今天学习了吗？"
            ).random()
            isCloudling -> listOf(
                "好好吃！谢谢主人~",
                "吃饱了，光芒闪闪！",
                "吃饱了，好满足！",
                "这个味道不错~",
                "还要还要！嘿嘿~",
                "就这？我吃的比你的绩点还少！",
                "还行吧，比食堂好吃一点点~",
                "你喂我的时候笑得那么开心，是不是有求于我？",
                "看在你喂我的份上，暂时不吐槽你了~",
                "不错不错，以后继续保持！",
                "吃饱了才有力气监督你学习！",
                "这就是传说中的投喂吗？我还要！",
                "总算想起来喂我了？我还以为你把我忘了呢",
                "吃人嘴软...但我还是要说，你今天学习了吗？"
            ).random()
            else -> listOf(
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
                "这就是传说中的投喂吗？我还要！",
                "总算想起来喂我了？我还以为你把我忘了喵！",
                "吃人嘴软...但我还是要说，你今天学习了吗喵？"
            ).random()
        }
    }

    fun play() {
        viewModelScope.launch {
            var played = false
            petMutex.withLock {
                val pet = _pet.value ?: return@withLock
                if (!pet.canPlay()) {
                    val mins = (pet.nextPlayRecoverIn() / 60_000).toInt()
                    _bubbleText.value = "我有点累了~ ${mins}分钟后再陪我玩吧，你以为我是永动机吗？"
                    delay(2000)
                    _bubbleText.value = null
                    return@withLock
                }
                pet.play()
                pet.lastInteractTime = System.currentTimeMillis()
                pet.state = PetState.HAPPY
                _bubbleText.value = getPlayResponse()
                _pet.value = pet
                withContext(Dispatchers.IO) { petRepository.savePet(pet) }
                played = true
            }
            if (!played) return@launch
            delay(3000)
            _bubbleText.value = null
            petMutex.withLock {
                val current = _pet.value ?: return@withLock
                updatePetState(current)
                _pet.value = current
                withContext(Dispatchers.IO) { petRepository.savePet(current) }
            }
        }
    }

    private fun getPlayResponse(): String {
        val pet = _pet.value
        val isCrab = pet?.petType == "crab"
        val isCloudling = pet?.petType == "cloudling"
        return when {
            isCrab -> listOf(
                "好好玩！再来一次！",
                "哈哈哈~ 和主人debug最开心了！",
                "和主人玩最开心了！",
                "嘿嘿~ 抓到你了！",
                "再玩再玩！",
                "你作业写完了吗就来玩？算了，我也是~",
                "这就是传说中的劳逸结合吗？逸得有点多啊~",
                "玩归玩，别忘了待会去学习！",
                "你确定不先把代码写了？那好吧~",
                "摸鱼一时爽，一直摸鱼一直爽！",
                "我怀疑你只是想找个借口不学习~",
                "玩够了没？你期末可不会陪你好玩~",
                "行吧行吧，陪你玩，谁让我摊上你了呢"
            ).random()
            isCloudling -> listOf(
                "好好玩！再来一次！",
                "飘飘飘~ 好开心！",
                "和主人玩最开心了！",
                "嘿嘿~ 抓到你了！",
                "再玩再玩！",
                "你作业写完了吗就来玩？算了，我也是~",
                "这就是传说中的劳逸结合吗？逸得有点多啊~",
                "玩归玩，别忘了待会去学习！",
                "你确定不先把代码写了？那好吧~",
                "摸鱼一时爽，一直摸鱼一直爽！",
                "我怀疑你只是想找个借口不学习~",
                "玩够了没？你期末可不会陪你好玩~",
                "行吧行吧，陪你玩，谁让我摊上你了呢"
            ).random()
            else -> listOf(
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
                "我怀疑你只是想找个借口不学习~",
                "玩够了没？你期末可不会陪你好玩~",
                "行吧行吧，陪你玩，谁让我摊上你了呢喵"
            ).random()
        }
    }

    fun onGradeChanged(isImproved: Boolean, courseName: String) {
        viewModelScope.launch {
            petMutex.withLock {
                val pet = _pet.value ?: return@withLock
                val isCrab = pet.petType == "crab"
                val isCloudling = pet.petType == "cloudling"
                val advice = if (isImproved) {
                    pet.mood = (pet.mood + 15).coerceAtMost(100)
                    pet.state = PetState.EXCITED
                    when {
                        isCrab -> listOf(
                            "${courseName}进步了！这波代码跑得漂亮！",
                            "成绩提高了！主人好厉害！",
                            "${courseName}考得不错！继续保持！",
                            "${courseName}进步了？是不是抄的？开玩笑的啦~",
                            "厉害了！是不是终于开始学习了？",
                            "${courseName}不错不错，看来我督促有效果！",
                            "哟，进步了？看来骂你还是有用的嘛~"
                        ).random()
                        isCloudling -> listOf(
                            "${courseName}进步了！太棒了！",
                            "成绩提高了！主人好厉害！",
                            "${courseName}考得不错！继续保持！",
                            "${courseName}进步了？是不是抄的？开玩笑的啦~",
                            "厉害了！是不是终于开始学习了？",
                            "${courseName}不错不错，看来我督促有效果！",
                            "哟，进步了？看来骂你还是有用的嘛~"
                        ).random()
                        else -> listOf(
                            "${courseName}进步了！太棒了喵~",
                            "成绩提高了！主人好厉害！",
                            "${courseName}考得不错！继续保持！",
                            "${courseName}进步了？是不是抄的？开玩笑的啦~",
                            "厉害了！是不是终于开始学习了？",
                            "${courseName}不错不错，看来我督促有效果！",
                            "哟，进步了？看来骂你还是有用的嘛喵~"
                        ).random()
                    }
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
                        "没关系，至少你还有我...虽然我也有点嫌弃你了~",
                        "${courseName}退步了？你说说你，是不是又偷懒了？",
                        "退步成这样，我都替你尴尬..."
                    ).random()
                }
                lastGradeAdvice = advice
                _bubbleText.value = advice
                _pet.value = pet
                withContext(Dispatchers.IO) { petRepository.savePet(pet) }
            }
            delay(5000)
            _bubbleText.value = null
        }
    }

    fun onExamComing(courseName: String, daysLeft: Int) {
        viewModelScope.launch {
            petMutex.withLock {
                val pet = _pet.value ?: return@withLock
                pet.state = PetState.EXAM_REMIND
                val isCrab = pet.petType == "crab"
                val isCloudling = pet.petType == "cloudling"
                val msg = when {
                    daysLeft == 0 -> when {
                        isCrab -> listOf(
                            "今天考${courseName}！代码都记住了吗？加油！",
                            "今天考${courseName}？你复习了吗？没有？那祝你好运！",
                            "${courseName}今天考！相信自己...虽然我不太信~",
                            "今天就考了？你昨天在干嘛？！现在才来抱佛脚？",
                            "我赌你今天要裸考...别让我赢"
                        ).random()
                        isCloudling -> listOf(
                            "今天考${courseName}！加油！",
                            "今天考${courseName}？你复习了吗？没有？那祝你好运！",
                            "${courseName}今天考！相信自己...虽然我不太信~",
                            "今天就考了？你昨天在干嘛？！现在才来抱佛脚？",
                            "我赌你今天要裸考...别让我赢"
                        ).random()
                        else -> listOf(
                            "今天考${courseName}！加油喵！",
                            "今天考${courseName}？你复习了吗？没有？那祝你好运！",
                            "${courseName}今天考！相信自己...虽然我不太信~",
                            "今天就考了？你昨天在干嘛？！现在才来抱佛脚喵？",
                            "我赌你今天要裸考...别让我赢~"
                        ).random()
                    }
                    daysLeft == 1 -> if (isCrab) listOf(
                        "明天就考${courseName}了，准备好了吗？",
                        "明天考${courseName}！今晚通宵预习还来得及！",
                        "明天就考了？你确定不现在开始看书？",
                        "最后一天了！你要是挂了别怪我没提醒你！"
                    ).random() else listOf(
                        "明天就考${courseName}了，准备好了吗？",
                        "明天考${courseName}！今晚通宵预习还来得及！",
                        "明天就考了？你确定不现在开始看书？",
                        "最后一天了！你要是挂了别怪我没提醒你！"
                    ).random()
                    daysLeft <= 3 -> if (isCrab) listOf(
                        "还有${daysLeft}天就考${courseName}了，该复习了！",
                        "还有${daysLeft}天考${courseName}，现在复习还来得及，再晚就真来不及了！",
                        "${courseName}还有${daysLeft}天，你的复习计划呢？什么？没有计划？",
                        "就剩${daysLeft}天了还在玩？你心真大啊！"
                    ).random() else listOf(
                        "还有${daysLeft}天就考${courseName}了，该复习了！",
                        "还有${daysLeft}天考${courseName}，现在复习还来得及，再晚就真来不及了！",
                        "${courseName}还有${daysLeft}天，你的复习计划呢？什么？没有计划？",
                        "就剩${daysLeft}天了还在玩？你心真大啊！"
                    ).random()
                    daysLeft <= 7 -> if (isCrab) listOf(
                        "${courseName}考试临近，开始复习吧~",
                        "下周就考${courseName}了，要不要我帮你划重点？开玩笑的，我又不会~",
                        "${courseName}快考了，你是不是该减少摸我的时间了？",
                        "还有${daysLeft}天，现在开始算临时抱佛脚，再晚就算临阵脱逃了"
                    ).random() else listOf(
                        "${courseName}考试临近，开始复习吧~",
                        "下周就考${courseName}了，要不要我帮你划重点？开玩笑的，我又不会~",
                        "${courseName}快考了，你是不是该减少摸我的时间了？",
                        "还有${daysLeft}天，现在开始算临时抱佛脚，再晚就算临阵脱逃了"
                    ).random()
                    else -> return@withLock
                }
                _bubbleText.value = msg
                _pet.value = pet
                withContext(Dispatchers.IO) { petRepository.savePet(pet) }
            }
            delay(5000)
            _bubbleText.value = null
        }
    }

    fun dismissLevelUp() {
        _showLevelUp.value = null
    }

    fun getLastGradeAdvice(): String? = lastGradeAdvice

    fun checkIn() {
        viewModelScope.launch {
            petMutex.withLock {
                val pet = _pet.value ?: return@withLock
                if (pet.isCheckedInToday()) {
                    _checkInResult.value = CheckInResult(0, pet.checkInStreak, true)
                    return@withLock
                }
                val oldPoints = pet.points
                pet.checkIn()
                _checkInResult.value = CheckInResult(pet.points - oldPoints, pet.checkInStreak, false)
                _pet.value = pet
                withContext(Dispatchers.IO) { petRepository.savePet(pet) }
            }
        }
    }

    fun dismissCheckInResult() {
        _checkInResult.value = null
    }

    fun triggerRandomBubble(todayCourseCount: Int, nextExam: Pair<String, Int>?, countdownEvents: List<Pair<String, Int>> = emptyList()) {
        val pet = _pet.value ?: return
        val now = System.currentTimeMillis()
        if (now - lastBubbleTime < 30_000) return // 30秒内不重复冒泡

        val isCrab = pet.petType == "crab"
        val isCloudling = pet.petType == "cloudling"
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val messages = mutableListOf<String>()
        when {
            pet.state == PetState.HUNGRY -> messages.add(when {
                isCrab -> "能量不足了...喂点东西？你以为我是永动机吗？"
                isCloudling -> "我的光芒变暗了...需要补充能量！你以为我是太阳能的吗？"
                else -> "肚子好饿...能喂我吃点东西吗？再不喂我就绝食喵！"
            })
            pet.state == PetState.SLEEPING -> messages.add("zzz...呼噜...")
            pet.mood < 20 && pet.hunger < 20 -> when {
                isCrab -> messages.addAll(listOf(
                    "又饿心情又差...你到底是养宠物还是虐待宠物？",
                    "我快不行了...又饿又难受...你是不是故意的？",
                    "你看看我现在什么状态！又饿又难过！你良心不会痛吗？"
                ))
                isCloudling -> messages.addAll(listOf(
                    "又饿心情又差...你到底是养宠物还是虐待宠物？",
                    "我快不行了...又饿又难受...你是不是故意的？",
                    "你看看我现在什么状态！又饿又难过！你良心不会痛吗？"
                ))
                else -> messages.addAll(listOf(
                    "又饿心情又差...你到底是养宠物还是虐待宠物喵？",
                    "我快不行了...又饿又难受...你是不是故意的？",
                    "你看看我现在什么状态！又饿又难过！你良心不会痛吗？"
                ))
            }
            hour in 0..5 -> when {
                isCrab -> messages.addAll(listOf(
                    "该睡觉了！", "别熬夜了！", "明天还有课呢~",
                    "你不要命啦？快去睡觉！",
                    "猝死新闻看少了是吧？",
                    "你是要修仙吗？明天的课怎么办！",
                    "都凌晨了还在玩手机？你是不是不要命了？",
                    "你再不睡我就把你手机关了！"
                ))
                isCloudling -> messages.addAll(listOf(
                    "该睡觉了！", "别熬夜了！", "明天还有课呢~",
                    "你不要命啦？快去睡觉！",
                    "猝死新闻看少了是吧？",
                    "你是要修仙吗？明天的课怎么办！",
                    "都凌晨了还在玩手机？你是不是不要命了？",
                    "你再不睡我就把你手机关了！"
                ))
                else -> messages.addAll(listOf(
                    "该睡觉了喵~", "别熬夜了！", "明天还有课呢~",
                    "你不要命啦？快去睡觉！",
                    "猝死新闻看少了是吧？",
                    "你是要修仙吗？明天的课怎么办！",
                    "都凌晨了还在玩手机？你是不是不要命了喵？",
                    "你再不睡我就把你手机关了喵！"
                ))
            }
            todayCourseCount > 0 && hour < 12 -> messages.addAll(listOf(
                "今天有${todayCourseCount}节课哦~",
                "还有${todayCourseCount}节课，惊不惊喜？",
                "${todayCourseCount}节课在等你，快起床！",
                "你还在赖床？${todayCourseCount}节课等着你呢！"
            ))
            todayCourseCount > 0 && hour >= 12 -> messages.addAll(listOf(
                "今天还剩课要上呢~",
                "课还没上完呢，别摆烂！",
                "下午的课在向你微笑~",
                "你是不是翘课了？别以为我不知道~"
            ))
            todayCourseCount == 0 && hour in 8..17 -> when {
                isCrab -> messages.addAll(listOf(
                    "今天没课，好好休息~",
                    "今天没课？那更要好好学（玩）习（耍）！",
                    "难得没课，别全睡过去了！",
                    "没课你就放飞自我了？作业写了吗？"
                ))
                isCloudling -> messages.addAll(listOf(
                    "今天没课，好好休息~",
                    "今天没课？那更要好好学（玩）习（耍）！",
                    "难得没课，别全睡过去了！",
                    "没课你就放飞自我了？作业写了吗？"
                ))
                else -> messages.addAll(listOf(
                    "今天没课，好好休息喵~",
                    "今天没课？那更要好好学（玩）习（耍）！",
                    "难得没课，别全睡过去了！",
                    "没课你就放飞自我了？作业写了吗喵？"
                ))
            }
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
            when {
                isCrab -> messages.addAll(listOf(
                    "在思考下一行代码该怎么写...", "摸摸我嘛~", "你在干什么呀？", "在敲键盘呢~",
                    "又在玩手机？看看书吧！",
                    "你的绩点在哭泣哦~",
                    "今天背单词了吗？没有？那摸我干嘛！",
                    "我虽然是虚拟的，但你的期末是真实的！",
                    "别摸我了，去写作业！",
                    "你再不学习，我就要替你去考试了！",
                    "我在替你的未来担忧呢~",
                    "摸鱼可以，但别忘了正事！",
                    "你是不是忘了什么？对，就是学习！",
                    "图书馆在向你招手哦~",
                    "听说挂科补考很痛苦的，我只是听说~",
                    "你已经盯着屏幕很久了，眼睛不酸吗？",
                    "我比你的绩点可爱多了对吧？",
                    "距离下次考试还有...算了不说了，怕你哭~",
                    "主人，今天努力了吗？",
                    "你以为养宠物就不用学习了？",
                    "我的快乐建立在你好好学习之上！",
                    "再不努力，毕业就要进厂拧螺丝了！",
                    "你看看你，又在无所事事了是吧？",
                    "你是不是觉得摸我就能变聪明？醒醒吧！",
                    "你有这时间摸我，不如去看两页书..."
                ))
                isCloudling -> messages.addAll(listOf(
                    "飘呀飘~", "摸摸我嘛~", "你在干什么呀？", "在发呆呢~",
                    "又在玩手机？看看书吧！",
                    "你的绩点在哭泣哦~",
                    "今天背单词了吗？没有？那摸我干嘛！",
                    "我虽然是虚拟的，但你的期末是真实的！",
                    "别摸我了，去写作业！",
                    "你再不学习，我就要替你去考试了！",
                    "我在替你的未来担忧呢~",
                    "摸鱼可以，但别忘了正事！",
                    "你是不是忘了什么？对，就是学习！",
                    "图书馆在向你招手哦~",
                    "听说挂科补考很痛苦的，我只是听说~",
                    "你已经盯着屏幕很久了，眼睛不酸吗？",
                    "我比你的绩点可爱多了对吧？",
                    "距离下次考试还有...算了不说了，怕你哭~",
                    "主人，今天努力了吗？",
                    "你以为养宠物就不用学习了？",
                    "我的快乐建立在你好好学习之上！",
                    "再不努力，毕业就要进厂拧螺丝了！",
                    "你看看你，又在无所事事了是吧？",
                    "你是不是觉得摸我就能变聪明？醒醒吧！",
                    "你有这时间摸我，不如去看两页书..."
                ))
                else -> messages.addAll(listOf(
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
                    "再不努力，毕业就要进厂拧螺丝了喵~",
                    "你看看你，又在无所事事了是吧喵？",
                    "你是不是觉得摸我就能变聪明？醒醒吧喵！",
                    "你有这时间摸我，不如去看两页书...喵~"
                ))
            }
        }

        lastBubbleTime = now
        _bubbleText.value = messages.random()
        viewModelScope.launch {
            delay(4000)
            _bubbleText.value = null
        }
    }

    fun getChatGreeting(todayCourseCount: Int, nextExamName: String?): String {
        val pet = _pet.value ?: return "你好！"
        val isCrab = pet.petType == "crab"
        val isCloudling = pet.petType == "cloudling"
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreet = when {
            hour < 6 -> "这么晚还没睡呀"
            hour < 12 -> "早上好"
            hour < 18 -> "下午好"
            else -> "晚上好"
        }
        val stateGreet = when (pet.state) {
            PetState.HUNGRY -> when {
                isCrab -> listOf("能量不足了...", "你要饿死我吗？！", "再不喂我我就翻桌了！", "来了？先喂饭再说别的！").random()
                isCloudling -> listOf("我的光芒变暗了...", "你要饿死我吗？！", "再不喂我我就翻桌了！", "来了？先喂饭再说别的！").random()
                else -> listOf("我有点饿了喵...", "你要饿死我吗？！", "再不喂我我就翻桌了！", "来了？先喂饭再说别的喵！").random()
            }
            PetState.SLEEPING -> listOf("好困...但你来了我就醒啦！", "嗯...你来干嘛...人家在睡觉...", "打扰我睡觉是要付出代价的！", "你来了？...等等我先打完这个哈欠...").random()
            PetState.HAPPY -> listOf("见到你超开心的！", "今天心情不错，允许你摸一下！", "你来啦！我刚把你的作业藏好了~", "哼，虽然我很开心，但别以为我是因为想你！").random()
            PetState.SAD -> listOf("不太开心...陪我聊会吧", "哼！你终于想起我了？", "你知道我等了你多久吗？", "你还知道来？我都等了多久了？！").random()
            PetState.STUDYING -> listOf("学习中！一起加油！", "别打扰我，我在替你学习！", "你学你的，我学我的，咱们各凭本事！", "嘘...别吵，我在替你学习呢！").random()
            PetState.EXAM_REMIND -> listOf("考试要来了！一起冲刺！", "完了完了要考试了！什么？是你考不是我？那没事了~", "距离挂科还有...啊不，距离考试还有几天！", "你还有心情来找我？快去复习！").random()
            else -> listOf("嘿嘿~", "哟，来了？", "今天又来摸鱼了？", "来了？这次准备摸多久？").random()
        }
        val courseInfo = if (todayCourseCount > 0) "今天有${todayCourseCount}节课，" else ""
        val examInfo = if (nextExamName != null) "${nextExamName}快考了，" else ""
        return "${timeGreet}主人！${stateGreet} ${courseInfo}${examInfo}有什么想聊的吗？"
    }

    fun onExamProgressUpdate(examName: String, newStatus: Int) {
        viewModelScope.launch {
            petMutex.withLock {
                val pet = _pet.value ?: return@withLock
                when (newStatus) {
                    1 -> {
                        pet.mood = (pet.mood + 3).coerceAtMost(100)
                        _bubbleText.value = "${examName}开始复习了，加油${if (pet.petType in listOf("crab", "cloudling")) "！" else "喵~"}"
                    }
                    2 -> {
                        pet.mood = (pet.mood + 10).coerceAtMost(100)
                        pet.state = PetState.HAPPY
                        _bubbleText.value = "太棒了，${examName}搞定了！"
                    }
                }
                _pet.value = pet
                withContext(Dispatchers.IO) { petRepository.savePet(pet) }
            }
            delay(3000)
            _bubbleText.value = null
        }
    }
}
