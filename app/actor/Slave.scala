package actor


import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.inject.Inject

import akka.actor.{Actor, Props}
import models.dao.{AutoResponseDao, GroupDao, KeywordResponseDao, MemberDao}
import play.api.Logger
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsObject, JsValue, Json}
import util.{HttpUtil, ReplyUtil, SecureUtil}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import concurrent.duration._
import scala.collection.mutable
import scala.collection.JavaConversions._

/**
  * Created by Macbook on 2017/4/13.
  */

object Slave{

  def props(userInfo: UserInfo,
            httpUtil: HttpUtil,
            keywordResponseDao:KeywordResponseDao,
            memberDao: MemberDao,
            autoResponseDao: AutoResponseDao,
            groupDao: GroupDao) = Props(new Slave(userInfo,httpUtil,keywordResponseDao,memberDao,autoResponseDao,groupDao))
}

class Slave @Inject() (userInfo: UserInfo,
                       httpUtil: HttpUtil,
                       keywordResponseDao:KeywordResponseDao,
                       memberDao: MemberDao,
                       autoResponseDao: AutoResponseDao,
                       groupDao: GroupDao)  extends Actor with ActorProtocol {

  private final val log = Logger(this.getClass)
  log.debug("------------------  Slave created")


  val groupMap = new scala.collection.mutable.HashMap[String, scala.collection.mutable.HashMap[String, String]]()
  val memberMap = new scala.collection.mutable.HashMap[String, String]()


  @throws[Exception](classOf[Exception])
  override def preStart(): Unit = {
    log.info(s"${self.path.name} starting...")
  }

  @throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    log.info(s"${self.path.name} stopping...")
  }

  def createUserNameData(seq: Seq[String]): Seq[JsObject] = {
    seq.map { m =>
      Json.obj(
        "UserName" -> m,
        "EncryChatRoomId" -> ""
      )
    }
  }

  def createSyncKey(Synckey: JsObject): String = {
    (Synckey \ "List").as[Seq[JsValue]].map { m =>
      val key = (m \ "Key").as[Int]
      val value = (m \ "Val").as[Long]
      key + "_" + value
    }.mkString("|")
  }

  def saveContactInfo(contactList:Seq[JsValue]) = {

    log.info("开始持久化通讯录详细信息...")
    
    val specialList = Set("newsapp", "fmessage", "filehelper", "weibo", "qqmail",
    "fmessage", "tmessage", "qmessage", "qqsync", "floatbottle",
    "lbsapp", "shakeapp", "medianote", "qqfriend", "readerapp",
    "blogapp", "facebookapp", "masssendapp", "meishiapp",
    "feedsapp", "voip", "blogappweixin", "weixin", "brandsessionholder",
    "weixinreminder", "wxid_novlwrv3lqwv11", "gh_22b87fa7cb3c",
    "officialaccounts", "notification_messages",
     "wxitil", "userexperience_alarm")

    contactList.par.foreach { contact =>
      val verifyFlag = (contact \ "VerifyFlag").as[Int]
      val userName = (contact \ "UserName").as[String]
      val nickName = (contact \ "NickName").as[String]
      val headImgUrl = (contact \ "HeadImgUrl").as[String]

      val province = (contact \ "Province").as[String]
      val city = (contact \ "City").as[String]
      val sex = (contact \ "Sex").as[Int]
      val alias = (contact \ "Alias").as[String]
      if((verifyFlag & 8) != 0){ // 公众号
        userInfo.PublicUsersList.put(userName,BaseInfo(nickName,"",headImgUrl,province,city,sex))
      }
      else if(specialList.contains(userName)){//特殊账号
        userInfo.SpecialUsersList.put(userName,BaseInfo(nickName,"",headImgUrl,province,city,sex))
      }
      else if(userName.startsWith("@@")){//群
        userInfo.GroupList.put(userName,BaseInfo(nickName,"",headImgUrl,province,city,sex))
      }
      else if(userName.equals(userInfo.username)){//自己
//        userInfo.PublicUsersList.put(userName,BaseInfo(nickName,headImgUrl,province,city,sex))
        userInfo.selfInfo = BaseInfo(nickName,"",headImgUrl,province,city,sex)
      }
      else{//好友
        userInfo.ContactList.put(userName,BaseInfo(nickName,"",headImgUrl,province,city,sex))
      }
    }
    if(userInfo.GroupList.nonEmpty){
      self ! GetGroupContect(userInfo.GroupList.keys().toArray)
    }

  }

  def parseUserName(userName:String):BaseInfo = {
    if(userInfo.PublicUsersList.containsKey(userName)){ // 公众号
      userInfo.PublicUsersList.get(userName)
    }
    else if(userInfo.SpecialUsersList.containsKey(userName)){//特殊账号
      userInfo.SpecialUsersList.get(userName)
    }
    else if(userInfo.GroupList.containsKey(userName)){//群
      userInfo.GroupList.get(userName)
    }
    else if(userName.equals(userInfo.username)){//自己
      userInfo.selfInfo
    }
    else{//好友
      userInfo.ContactList.get(userName)
    }
  }

//  def uploadFile(filePath:String):Option[String] = {
//    val file = new File(filePath)
//    if(file.exists()){
//      val flen = file.length()
//      val ftype = "application/octet-stream"
//
//    }
//    else{
//      None
//    }
//  }

  override def receive: Receive = {
    case SetGroupName(groupunionid,name) => //设置群聊名称
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxupdatechatroom"
      val cookies = userInfo.cookie

      val params = List(
        "fun" -> "modtopic",
        "pass_ticket" -> userInfo.pass_ticket
      )
      val postData = Json.obj(
        "BaseRequest" -> Json.obj(
          "Uin" -> userInfo.wxuin,
          "Sid" -> userInfo.wxsid,
          "Skey" -> userInfo.skey,
          "DeviceID" -> userInfo.deviceId
        ),
        "NewTopic" -> name,
        "ChatRoomName" -> groupunionid
      )
      httpUtil.postJsonRequestSend("add user to group", baseUrl, params, postData, cookies).map { js =>
        try {
          val ret = (js \ "BaseResponse" \ "Ret").as[Int]
          if(ret == 0){
            log.info(s"设置群名称成功:\r\n群:$groupunionid \r\n新名称:$name \r\n")
            groupDao.changeGroupNickName(groupunionid,name).map{res =>
              if(res > 0){
                log.info(s"数据库更新群名称成功:群:$groupunionid 新名称:$name")
              }
            }
          }
          else{
            val errMsg = (js \ "BaseResponse" \ "ErrMsg").as[String]
            log.info(s"设置群名称失败，群$groupunionid 新名称:$name 原因：ret:$ret errormsg:$errMsg")
          }
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error:" + js + s"ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("add user to group with EXCEPTION：" + e.getMessage)
      }
    case InviteUserToGroup(userunionid,groupunionid) => //间接邀请新人入群，当群成员数大于100时需要用这种方式邀请传入群昵称和成员唯一id
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxupdatechatroom"
      val cookies = userInfo.cookie

      val params = List(
        "fun" -> "invitemember",
        "pass_ticket" -> userInfo.pass_ticket
      )
      val postData = Json.obj(
        "BaseRequest" -> Json.obj(
          "Uin" -> userInfo.wxuin,
          "Sid" -> userInfo.wxsid,
          "Skey" -> userInfo.skey,
          "DeviceID" -> userInfo.deviceId
        ),
        "InviteMemberList" -> userunionid,
        "ChatRoomName" -> groupunionid
      )
      httpUtil.postJsonRequestSend("add user to group", baseUrl, params, postData, cookies).map { js =>
        try {
          val ret = (js \ "BaseResponse" \ "Ret").as[Int]
          if(ret == 0){
            log.info(s"间接邀请群成员成功:\r\n群:$groupunionid \r\n成员:$userunionid \r\n")
          }
          else{
            val errMsg = (js \ "BaseResponse" \ "ErrMsg").as[String]
            log.info(s"间接邀请群成员失败，群$groupunionid 成员:$userunionid 原因：ret:$ret errormsg:$errMsg")
          }
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error:" + js + s"ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("add user to group with EXCEPTION：" + e.getMessage)
      }
    case AddUserToGroup(userunionid,groupunionid) => //直接邀请新人入群，传入群昵称和成员唯一id
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxupdatechatroom"
      val cookies = userInfo.cookie

      val params = List(
        "fun" -> "addmember",
        "pass_ticket" -> userInfo.pass_ticket
      )
      val postData = Json.obj(
        "BaseRequest" -> Json.obj(
          "Uin" -> userInfo.wxuin,
          "Sid" -> userInfo.wxsid,
          "Skey" -> userInfo.skey,
          "DeviceID" -> userInfo.deviceId
        ),
        "AddMemberList" -> userunionid,
        "ChatRoomName" -> groupunionid
      )
      httpUtil.postJsonRequestSend("add user to group", baseUrl, params, postData, cookies).map { js =>
        try {
          val ret = (js \ "BaseResponse" \ "Ret").as[Int]
          if(ret == 0){
            log.info(s"直接邀请群成员成功:\r\n群:$groupunionid \r\n成员:$userunionid \r\n")
          }
          else{
            val errMsg = (js \ "BaseResponse" \ "ErrMsg").as[String]
            log.info(s"直接邀请群成员失败，群$groupunionid 成员:$userunionid 原因：ret:$ret errormsg:$errMsg")
            self ! InviteUserToGroup(userunionid,groupunionid)
          }
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error:" + js + s"ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("add user to group with EXCEPTION：" + e.getMessage)
      }
    case DeleteUserFromGroup(userunionid,groupunionid) => //踢出群成员，传入群昵称和成员昵称（不是displayName）
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxupdatechatroom"
      val cookies = userInfo.cookie

      val params = List(
        "fun" -> "delmember",
        "pass_ticket" -> userInfo.pass_ticket
      )
      val postData = Json.obj(
        "BaseRequest" -> Json.obj(
          "Uin" -> userInfo.wxuin,
          "Sid" -> userInfo.wxsid,
          "Skey" -> userInfo.skey,
          "DeviceID" -> userInfo.deviceId
        ),
        "DelMemberList" -> userunionid,
        "ChatRoomName" -> groupunionid
      )
      httpUtil.postJsonRequestSend("delete user from group", baseUrl, params, postData, cookies).map { js =>
        try {
          log.debug("踢出群返回消息："+js)
          val ret = (js \ "BaseResponse" \ "Ret").as[Int]
          if(ret == 0){
            log.info(s"踢出群成员成功:\r\n群:$groupunionid \r\n成员:$userunionid \r\n")
          }
          else{
            val errMsg = (js \ "BaseResponse" \ "ErrMsg").as[String]
            log.info(s"踢出群成员失败，群$groupunionid 成员:$userunionid 原因：ret:$ret errormsg:$errMsg")
          }
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error:" + js + s"ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("delete user from group with EXCEPTION：" + e.getMessage)
      }
    case BeginInit() =>
//      val schedule = context.system.scheduler.schedule(5.second ,1.day,self,BeginInit())
//      schedule.cancel()
      log.info("开始准备与微信建立链接")
      self ! GetTicketAndKey()
    case HandleMsg(fromUserName,toUserName,msgType,msg) => // 处理消息细节
      val content = (msg \ "Content").as[String]

      val memName = content.split(":<br/>")(0)

      val groupInfo = Await.result(groupDao.getGroupByUnionId(fromUserName),10.second)
      val memberInfo = Await.result(memberDao.getMemberByUnionId(memName),10.second)
      var groupName = "未知"
      var memberName = "未知" //
      if(groupInfo.isDefined && memberInfo.isDefined) {
        groupName = groupInfo.get.groupnickname
        memberName = if (memberInfo.get.userdisplayname.equals("")) memberInfo.get.usernickname else memberInfo.get.userdisplayname
      }//@3289701fdde4ad3656abb401ba33c78e @3289701fdde4ad3656abb401ba33c78e
      log.info(s"收到新消息【$msg】")
        msgType match {
          case 1 => // 文本消息
            if (content.contains("@李暴龙")) { //是否开启自动聊天
              val info = content.split("@李暴龙")

              if (info.length > 1) {
                val msg = info(1).replace(" ", "").trim() // 把@姓名 后面的特殊空格去掉并去掉首尾的空格
                if (msg == "") {
//                  val response = "[疑问]"
//                  self ! SendMessage(response, userInfo.username, fromUserName)
                  self ! SendEmotionMessage("1f98d5d1f74960172e7a8004b1054f5b", userInfo.username, fromUserName)
                }
                else {
                  //                    val response = Await.result(chatApi.chatWithRobot(msg, ""), 27.seconds)
                  val response = null
                  if (response == null) {
                  }
                  else {
                    //                      Await.result(sendMessage(passTicket, uin, sid, skey, deviceId, username, formUserName, response, cookies), 27.seconds)
                    self ! SendMessage(response, userInfo.username, fromUserName)
                  }
                }
              }
            }
            else {
              if(content.contains("哈哈哈哈哈哈哈哈哈哈") && groupName.equals("盖世英雄")){
                self ! SendEmotionMessage("4fe01247c319c06b9d4a12f9e939b114", userInfo.username, fromUserName)
              }
              if(content.contains("@不二法师") && groupName.equals("盖世英雄")){
                self ! SendEmotionMessage("2ef2b73bf17b4a0921b14b1638601229", userInfo.username, fromUserName)
              }
              //是否有满足关键词回复
              val keywordList = Await.result(keywordResponseDao.getKeywordResponseList(userInfo.userid,groupName), 10.second)
              val response = ReplyUtil.autoReply(content, keywordList)
              if (response != null) {
                self ! SendMessage(response, toUserName, fromUserName)
              }
            }
            if(fromUserName.startsWith("@@")) {
              val text = content.split(":<br/>")(1)
              if(text.contains("退群")){ // 触发退群关键字
                self ! DeleteUserFromGroup(memName,fromUserName)
              }
              if(text.startsWith("改名")){
                val newName = text.substring(3,text.length)
                self ! SetGroupName(fromUserName,newName)
              }
              log.info(s"\r\n收到文本消息(type:$msgType)，来自：【$groupName】\r\n发送人：【$memberName】\r\n内容【$text】")
            }
            else{
              if(content.contains("入群")){ // 触发入群关键字
                val groupunionid = Await.result(groupDao.getGroupByName("盖世英雄",userInfo.userid),10.second).get.groupunionid
                self ! AddUserToGroup(fromUserName,groupunionid)
              }
              log.info(s"\r\n收到文本消息(type:$msgType)，来自：【$groupName】\r\n发送人：【$memberName】\r\n内容【$content】")
            }
          case 3 => // 图片消息
            val msgId = (msg \ "MsgId").as[String]
            val imgUrl = s"https://wx.qq.com/cgi-bin/mmwebwx-bin/webwxgetmsgimg?MsgID=$msgId&skey=${userInfo.skey}"
            log.info(s"\r\n收到图片消息(type:$msgType)，来自：【$groupName】\r\n发送人：【$memberName】\r\n图片地址【$imgUrl】")
            httpUtil.getFileRequestSend("get emotion file",imgUrl,userInfo.cookie,List()).map{ res =>
              if(res.isDefined){
                log.debug(s"下载文件[$imgUrl]成功,文件名：${res.get}")
              }
              else{
                log.debug(s"下载文件[$imgUrl]失败")
              }
            }
          //如果是图片消息，通过MsgId字段获取msgid，然后调用以下接口获取图片，type字段为空为大图，否则是缩略图
          //https://wx.qq.com/cgi-bin/mmwebwx-bin/webwxgetmsgimg?MsgID=4880689959463718121&type=slave&skey=@crypt_f6c3cb1f_8b158d6e5d7df945580d590bd7612083
          case 34 => // 语音消息
            val msgId = (msg \ "MsgId").as[String]
            val voiceUrl = s"https://wx.qq.com/cgi-bin/mmwebwx-bin/webwxgetvoice?MsgID=$msgId&skey=${userInfo.skey}"
            log.info(s"\r\n收到语音消息(type:$msgType)，来自：【$groupName】\r\n发送人：【$memberName】\r\n语音地址【$voiceUrl】")
          case 42 => //名片消息
            val recommendInfo = (msg \ "RecommendInfo").as[JsObject]
            val cardNickName = (recommendInfo \ "NickName").as[String]
            val cardAlias = (recommendInfo \ "Alias").as[String]
            val cardProvince = (recommendInfo \ "Province").as[String]
            val cardCity = (recommendInfo \ "City").as[String]
            val cardSex = (recommendInfo \ "Sex").as[Int] match {
              case 0 => "unknow"
              case 1 => "male"
              case 2 => "female"
              case _ => "unknow"
            }
            log.info(s"\r\n收到名片消息(type:$msgType)，来自：【$groupName】\r\n发送人：【$memberName】%内容【名称:$cardNickName 别名:$cardAlias 省份:$cardProvince 城市:$cardCity 性别:$cardSex 】")
          case 47 => // 动画表情
            if (content.contains("cdnurl = ")) {
//              val cdnurl = content.split("cdnurl = \"")(1).split("\"")(0)
              val pattern = Pattern.compile(""".*md5\s?=\s?"(.*?)".*cdnurl\s?=\s?"(.*?)".*""")
              val matcher = pattern.matcher(content)
              if(matcher.matches()){
                val md5 = matcher.group(1)
                val cdnurl = matcher.group(2)
                log.info(s"\r\n收到动画表情(type:$msgType)，来自：【$groupName】\r\n发送人：【$memberName】表情地址【$cdnurl】MD5【$md5】")
                httpUtil.getFileRequestSend("get emotion file",cdnurl,userInfo.cookie,List(),md5).map{ res =>
                  if(res.isDefined){
                    log.debug(s"下载文件[$cdnurl]成功,文件名：${res.get}")
                  }
                  else{
                    log.debug(s"下载文件[$cdnurl]失败")
                  }
                }
              }
            }
          // content字段里除了发送人id，还会有一个cdnurl字段，里面是动画表情的地址
          //Content":"@b1314d7ff68c30ceb8617667ca9eabfe:<br/>&lt;msg&gt;&lt;emoji fromusername = \"Suk_Ariel\" tousername = \"7458242548@chatroom\" type=\"2\" idbuffer=\"media:0_0\" md5=\"89fb4ee355c265bacee2766bec232a5e\" len = \"5372\" productid=\"\" androidmd5=\"89fb4ee355c265bacee2766bec232a5e\" androidlen=\"5372\" s60v3md5 = \"89fb4ee355c265bacee2766bec232a5e\" s60v3len=\"5372\" s60v5md5 = \"89fb4ee355c265bacee2766bec232a5e\" s60v5len=\"5372\" cdnurl = \"http://emoji.qpic.cn/wx_emoji/weXlICicxWBYKtZe4tu8YALX7CLJMXRiaV3J7vpBJ7KN2efLxbYjFkYw/\" designerid = \"\" thumburl = \"\" encrypturl = \"http://emoji.qpic.cn/wx_emoji/weXlICicxWBYKtZe4tu8YALX7CLJMXRiaVOZv37pyB6NIx3MNSzIqKvA/\" aeskey= \"07260f60e0d2c9f26a4ed3d5ea5ffd39\" width= \"48\" height= \"48\" &gt;&lt;/emoji&gt; &lt;/msg&gt;"
          case 48 => // 位置消息
            val address = content.split("<br/>")(0)
            val url = (msg \ "Url").as[String]
            log.info(s"\r\n收到位置消息(type:$msgType)，来自：【$groupName】\r\n发送人：【$memberName】%内容【位置：$address 地址：$url】")
          case 49 => // 分享链接
            val url = (msg \ "Url").as[String]
          //              val url = (msg \ "Url").as[String]
          //              if (url.startsWith("http://yys.163.com/h5/time")) {
          //                log.debug("receive URL:" + url)
          //                val sharePage = url.split("share_page=")(1).split("&")(0)
          //                val jieguo = shuapiao(sharePage)
          //                val total = jieguo._1
          //                val expire = jieguo._2
          //                val win = jieguo._3
          //
          //                var sendUserName = ""
          //                var realContent = ""
          //                var sendDisplayName = ""
          //                if(formUserName.startsWith("@@")) {//如果是群消息
          //                  sendUserName = content.split(":<br/>")(0)
          //                  sendDisplayName = groupMap.getOrElse(formUserName, new HashMap[String, String]).getOrElse(sendUserName, "")
          //                  realContent = content.split(":<br/>")(1)
          //                }
          //
          //                //@<span class=\"emoji emoji1f433\"></span> 樂瑥（海豚emoji表情）
          //                val str = s"@${sendDisplayName} 收到活动链接，一番努力后总共已经点亮了${total - expire}个爱心(新增${win}个)(上限10个)，快上游戏看积分有没增加吧~(账号总数:${total} 失效:${expire} 在线:${total - expire} 新增点亮:${win} 重复点亮:${total - expire - win})"
          //                Await.result(sendMessage(passTicket, uin, sid, skey, deviceId, username, formUserName, str, cookies), 27.seconds)
          //              }
            log.info(s"\r\n收到位置消息(type:$msgType)，来自：【$groupName】\r\n发送人：【$memberName】\r\n链接地址【$url】")
          case 51 => //在手机上操作了微信 FromUserName:本微信号@c7d30d0f06f8e66deff3113c05ae22d9 ToUserName:打开聊天窗口的微信号filehelper StatusNotifyUserName：打开聊天窗口的微信号filehelper

            val statusNotifyCode = (msg \ "StatusNotifyCode").as[Int]
            val statusNotifyUserName = (msg \ "StatusNotifyUserName").as[String]
            if(statusNotifyUserName.length > 0 && statusNotifyCode == 4) {
              val groupNotifyList = statusNotifyUserName.split(",").filter(m => m.startsWith("@@"))
//              log.info(s"联系人有更新(type:$msgType):$msg")//这时FromUserName是自己，ToUserName是其他人或群
              self ! GetGroupContect(groupNotifyList)
            }
            else{
              log.info(s"(type:$msgType)在手机上操作了微信：$content")
            }
          case 62 => // 小视频
            val msgId = (msg \ "MsgId").as[String]
            val videoUrl = s"https://wx.qq.com/cgi-bin/mmwebwx-bin/webwxgetvideo?MsgID=$msgId&skey=${userInfo.skey}"
            log.info(s"\r\n收到视频消息(type:$msgType)，来自：【$groupName】\r\n发送人：【$memberName】%视频地址【$videoUrl】")
          case 10000 => // 系统消息
            log.info(s"\r\n收到系统消息(type:$msgType)，内容：【$content】来自:【$groupName】")
            //TODO 新人邀请 "FromUserName":"@@131473cf33f36c70ea5a95ce6c359a9e35f32c0ffbddf2e59e242f6a823ff2fa","ToUserName":"@fb6dce95633e13ca08e966a6f9a34e3c","MsgType":10000,"Content":"\" <span class=\"emoji emoji1f338\"></span>卷卷卷<span class=\"emoji emoji1f338\"></span>\"邀请\"Hou$e\"加入了群聊
            val groupNickName = Await.result(groupDao.getGroupByUnionId(fromUserName),10.second).get.groupnickname
            if(groupNickName.equals("盖世英雄")) { //Todo 注释掉这里应用到全部群组中
              if (content.contains("加入了群聊")) {
                val pattern = Pattern.compile("""(.*?)邀请\"(.*?)\"加入了群聊""")
                val matcher = pattern.matcher(content)
                val inviter = matcher.group(1)
                val invitee = matcher.group(2)
                autoResponseDao.getAutoresponseByGroupNickName(userInfo.userid,groupNickName).map{ responseOpt =>
                  if(responseOpt.isDefined){
                    log.info(s"$inviter 邀请 $invitee 加入了群聊")
                    self ! SendMessage(responseOpt.get.response.replaceAll("@被邀请人",s"@$invitee "), toUserName, fromUserName)
                  }
                }
              }
              else if (content.contains("移出了群聊")) {
                val pattern = Pattern.compile("""(.*?)将\"(.*?)\"移出了群聊""")
                val matcher = pattern.matcher(content)
                val inviter = matcher.group(1)
                val invitee = matcher.group(2)
                log.info(s"$inviter 将 $invitee 移出了群聊")
                self ! SendMessage(s"$invitee 被移出了群聊", toUserName, fromUserName)
              }
            }
            if(content.contains("修改群名为")){
              val pattern = Pattern.compile(""".*?修改群名为.*?“(.*?)”.*?""")
              val matcher = pattern.matcher(content)
              if(matcher.matches()) {
                val newName = matcher.group(1)
                groupDao.changeGroupNickName(fromUserName, newName).map{res =>
                  if(res > 0){
                    log.info(s"数据库更新群名称成功:群:$fromUserName 新名称:$newName")
                  }
                }
              }
            }

          case 10002 => // 撤回消息
            log.info(s"\r\n【$groupName】撤回了一条消息(type:$msgType)，内容：【$content】")
          case _ => // 其他消息

        }
//      }
//      else{
//        log.error(s"找不到群或成员，群（$fromUserName）成员（$memName）")
//      }
    case SendImgMessage(mediaid: String, from: String, to: String) => //发送图片消息
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxsendmsgimg"
      val cookies = userInfo.cookie
      val curTime = System.currentTimeMillis()
      val LocalID = curTime + SecureUtil.nonceDigit(4)
      val params = List(
        "fun" -> "async",
        "f" -> "json",
        "pass_ticket" -> userInfo.pass_ticket
      )
      val postData = Json.obj(
        "BaseRequest" -> Json.obj(
          "Uin" -> userInfo.wxuin,
          "Sid" -> userInfo.wxsid,
          "Skey" -> userInfo.skey,
          "DeviceID" -> userInfo.deviceId
        ),
        "Msg" -> Json.obj(
          "Type" -> 3,
          "Content" -> "",
          "MediaId" -> mediaid, //要发送的消息
          "FromUserName" -> from, //自己ID
          "ToUserName" -> to, //好友ID
          "LocalID" -> LocalID, //与ClientMsgId相同
          "ClientMsgId" -> LocalID //时间戳左移4位加4位随机数
        ),
        "Scene" -> "0"

      )
      httpUtil.postJsonRequestSend("sendImgMessage", baseUrl, params, postData, cookies).map { js =>
        try {
          val ret = (js \ "BaseResponse" \ "Ret").as[Int]
          if(ret == 0){
            log.info(s"发送图片消息成功:\r\nfrom:$from \r\nto:$to \r\nMediaId:【$mediaid】")
          }
          else{
            val errMsg = (js \ "BaseResponse" \ "ErrMsg").as[String]
            log.info(s"发送图片消息失败，原因：ret:$ret errormsg:$errMsg")
          }
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error:" + js + s"ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("sendMessage with EXCEPTION：" + e.getMessage)
      }
    case SendEmotionMessage(mediaid: String, from: String, to: String) => //发送表情消息
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxsendemoticon"
      val cookies = userInfo.cookie
      val curTime = System.currentTimeMillis()
      val LocalID = curTime + SecureUtil.nonceDigit(4)
      val params = List(
        "fun" -> "sys",
        "pass_ticket" -> userInfo.pass_ticket
      )
      val postData = Json.obj(
        "BaseRequest" -> Json.obj(
          "Uin" -> userInfo.wxuin,
          "Sid" -> userInfo.wxsid,
          "Skey" -> userInfo.skey,
          "DeviceID" -> userInfo.deviceId
        ),
        "Msg" -> Json.obj(
          "Type" -> 47,
          "EMoticonMd5" -> mediaid, //发送表情，可是是表情的MD5或者uploadMedia返回的mediaId
          "FromUserName" -> from, //自己ID
          "ToUserName" -> to, //好友ID
          "LocalID" -> LocalID, //与ClientMsgId相同
          "ClientMsgId" -> LocalID //时间戳左移4位加4位随机数
        ),
        "Scene" -> "0"

      )
      httpUtil.postJsonRequestSend("sendImgMessage", baseUrl, params, postData, cookies).map { js =>
        try {
          val ret = (js \ "BaseResponse" \ "Ret").as[Int]
          if(ret == 0){
            log.info(s"发送图片消息成功:\r\nfrom:$from \r\nto:$to \r\nMediaId:【$mediaid】")
          }
          else{
            val errMsg = (js \ "BaseResponse" \ "ErrMsg").as[String]
            log.info(s"发送图片消息失败，原因：ret:$ret errormsg:$errMsg")
          }
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error:" + js + s"ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("sendMessage with EXCEPTION：" + e.getMessage)
      }
    case SendMessage(msg: String, from: String, to: String) => //发送文本消息
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxsendmsg"
      val cookies = userInfo.cookie
      val curTime = System.currentTimeMillis()
      val LocalID = curTime + SecureUtil.nonceDigit(4)
      val params = List(
        "pass_ticket" -> userInfo.pass_ticket
      )
      val postData = Json.obj(
        "BaseRequest" -> Json.obj(
          "Uin" -> userInfo.wxuin,
          "Sid" -> userInfo.wxsid,
          "Skey" -> userInfo.skey,
          "DeviceID" -> userInfo.deviceId
        ),
        "Msg" -> Json.obj(
          "Type" -> "1",
          "Content" -> msg, //要发送的消息
          "FromUserName" -> from, //自己ID
          "ToUserName" -> to, //好友ID
          "LocalID" -> LocalID, //与ClientMsgId相同
          "ClientMsgId" -> LocalID //时间戳左移4位加4位随机数
        ),
        "Scene" -> "0"

      )

      httpUtil.postJsonRequestSend("sendMessage", baseUrl, params, postData, cookies).map { js =>
        try {
          /*返回数据(JSON):
  {
      "BaseResponse": {
          "Ret": 0,
          "errMsg": ""
      },
      ...
  }
          */
          val ret = (js \ "BaseResponse" \ "Ret").as[Int]
          if(ret == 0){
            log.info(s"回复消息成功:\r\nfrom:$from \r\nto:$to \r\n内容:【$msg】")
          }
          else{
            val errMsg = (js \ "BaseResponse" \ "ErrMsg").as[String]
            log.info(s"回复消息失败，原因：ret:$ret errormsg:$errMsg")
          }
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error:" + js + s"ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("sendMessage with EXCEPTION：" + e.getMessage)
      }
    case ProcessNewMessage(msgList: Seq[JsValue]) => //处理收到的新消息
      if (msgList.nonEmpty) {
        msgList.foreach { msg =>
          val fromUserName = (msg \ "FromUserName").as[String]
          //@@0528655d6b576ac0ea5772ac3a41e42a1b4368aaad304c67b74f4c4696569d28
          val toUserName = (msg \ "ToUserName").as[String]
          val msgType = (msg \ "MsgType").as[Int]
          val content = (msg \ "Content").as[String]

          self ! HandleMsg(fromUserName,toUserName,msgType,msg)
          //TODO 统计群成员的消息，记录活跃状态,content构成[@sjkahdjkajsdjksd:<br/>msg][用户id:<br/>消息]
        }
      }
      else {
        log.debug("没有新消息....")
      }
    case ReceivedNewMessage() => // 获取新消息
//      log.info("ReceivedNewMessage")
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxsync"
      val cookies = userInfo.cookie
      val curTime = System.currentTimeMillis()
      val params = List(
        "sid" -> userInfo.wxsid,
        "skey" -> userInfo.skey,
        "pass_ticket" -> userInfo.pass_ticket,
        "lang" -> "zh_CN"
      )
      val postData = Json.obj(
        "BaseRequest" -> Json.obj(
          "Uin" -> userInfo.wxuin,
          "Sid" -> userInfo.wxsid,
          "Skey" -> userInfo.skey,
          "DeviceID" -> userInfo.deviceId
        ),
        "SyncKey" -> userInfo.SyncKey,
        "rr" -> (~curTime.toInt).toString
      )

      httpUtil.postJsonRequestSend("getMessage", baseUrl, params, postData, cookies).map { js =>
        try {
//          log.debug("getMessage with return msg:" + js)
          val ret = (js \ "BaseResponse" \ "Ret").as[Int]
          if (ret == 0) {
            //返回成功
            val addMsgCount = (js \ "AddMsgCount").as[Int]
            val addMsgList = (js \ "AddMsgList").as[Seq[JsValue]]
            val Synckey = (js \ "SyncKey").as[JsObject]
            val synckey = createSyncKey(Synckey)
            userInfo.SyncKey = Synckey
            userInfo.synckey = synckey
            if(addMsgList.nonEmpty)
              self ! ProcessNewMessage(addMsgList)
          }
          else {
            val errMsg = (js \ "BaseResponse" \ "ErrMsg").as[String]
            log.error(s"获取新消息失败，原因:ret:$ret errmsg:$errMsg")
          }
          self ! SyncCheck()
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error:" + js + s"ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("getMessage with EXCEPTION：" + e.getMessage)
      }
    case SyncCheckKey() => // 当synccheck返回的selector是4或6时，需要用syncCheckKey更新而不是syncKey
      log.info("收到通讯录更新消息,selector = 4")
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxsync"
      val cookies = userInfo.cookie
      val curTime = System.currentTimeMillis()
      val params = List(
        "sid" -> userInfo.wxsid,
        "skey" -> userInfo.skey,
        "pass_ticket" -> userInfo.pass_ticket,
        "lang" -> "zh_CN"
      )
      val postData = Json.obj(
        "BaseRequest" -> Json.obj(
          "Uin" -> userInfo.wxuin,
          "Sid" -> userInfo.wxsid,
          "Skey" -> userInfo.skey,
          "DeviceID" -> userInfo.deviceId
        ),
        "SyncKey" -> userInfo.SyncKey,
        "rr" -> (~curTime.toInt).toString
      )

      httpUtil.postJsonRequestSend("sync check key", baseUrl, params, postData, cookies).map { js =>
        try {
          log.debug("getMessage with return msg:" + js)
          val ret = (js \ "BaseResponse" \ "Ret").as[Int]
          if (ret == 0) {
            //返回成功
            val addMsgCount = (js \ "AddMsgCount").as[Int]
            val addMsgList = (js \ "AddMsgList").as[Seq[JsValue]]
            val SyncCheckKey = (js \ "SyncCheckKey").as[JsObject]
            val synccheckkey = createSyncKey(SyncCheckKey)
            userInfo.SyncKey = SyncCheckKey
            userInfo.synckey = synccheckkey
            if(addMsgList.nonEmpty)
              self ! ProcessNewMessage(addMsgList)
          }
          else {
            val errMsg = (js \ "BaseResponse" \ "ErrMsg").as[String]
            log.error(s"SyncCheckKey error,resaon:ret:$ret errmsg:$errMsg")
          }
          self ! SyncCheck()
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error:" + js + s"ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("sync check key with EXCEPTION：" + e.getMessage)
      }
    case SyncCheck() => //心跳检查，是否有新消息

      /*其中一个
      webpush.wx.qq.com
      webpush.weixin.qq.com
      webpush2.weixin.qq.com

      webpush.wechat.com
      webpush1.wechat.com
      webpush2.wechat.com
      webpush.wechatapp.com
      webpush1.wechatapp.com

       */
       val baseUrl = "http://" + userInfo.syncHost + userInfo.base_uri + "cgi-bin/mmwebwx-bin/synccheck"
        val cookies = userInfo.cookie
        val curTime = System.currentTimeMillis().toString
        val params = List(
          "skey" -> userInfo.skey,
          "synckey" -> userInfo.synckey,
          "deviceid" -> userInfo.deviceId, //e346659865782051
          "uin" -> userInfo.wxuin,
          "sid" -> userInfo.wxsid,
          "r" -> curTime,
          "_" -> System.currentTimeMillis().toString
        )
        httpUtil.getBodyRequestSend("synccheck", baseUrl, params, cookies).map { body =>
          try {
            /*返回数据(String):
          window.synccheck={retcode:"xxx",selector:"xxx"}
          retcode:
              0 正常
              1100 从微信客户端上登出
              1101 从其它设备上登了网页微信
          selector:
              0 正常
              2 新的消息
              4 通讯录更新
              6 可能是红包
              7 进入/离开聊天界面
          */
            log.info(s"用户[${userInfo.userid}] Host:[${userInfo.syncHost}] 收到心跳消息:${body.toString.split("=")(1)} ")
            val retcode = body.split("=")(1).split("\"")(1)
            val selector = body.split("=")(1).split("\"")(3)
            if (retcode.equals("0")) {
              if (selector.equals("2")) { //收到新消息
                self ! ReceivedNewMessage()
              }
              else if(selector.equals("0")){
                Thread.sleep(1000)
                self ! SyncCheck()
              }
              else if(selector.equals("4") || selector.equals("6")){ //4-更新通讯录信息
                self ! SyncCheckKey()
//                log.error("失去链接，原因retcode:" + retcode + " selector:" + selector)
//                self ! PoisonPill
              }
              else{
                self ! SyncCheck()
              }
            }
            else if(retcode.equals("1100")){
              log.info("retcode:1100 -> userid:" + userInfo.userid + " 从其他设备登入了网页版微信")
              context.parent ! SlaveStop(userInfo.userid)

            }
            else if(retcode.equals("1101")){
              log.info("retcode:1101 -> userid:" + userInfo.userid + " 手动登出了微信")
              context.parent ! SlaveStop(userInfo.userid)
            }
            else{
              log.info(s"retcode:$retcode -> SyncHost（${userInfo.syncHost}）失效,更换新host")
//              userInfo.syncHost = "webpush2."
              self ! SyncCheck()
            }
          } catch {
            case ex: Throwable =>
              ex.printStackTrace()
              log.error(s"error:" + body + s"ex: $ex")
          }
        }.onFailure { //Todo 这里可能会超时，需要重新更换线路
          case e: Exception =>
            log.error("sync check with EXCEPTION：" + e.getMessage)
            userInfo.syncHost = "webpush2." //Todo 重新找合适的host
            self ! SyncCheck()
        }
    case GetGroupContect(chatset) => // 获取群组详细信息
      log.debug("开始批量获取群组详细信息，群组数量:"+chatset.length)
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxbatchgetcontact"

      val cookies = userInfo.cookie
      val curTime = System.currentTimeMillis().toString
      val userList = createUserNameData(chatset)
      val count = userList.length
      val params = List(
        "pass_ticket" -> userInfo.pass_ticket,
        "type" -> "ex",
        "r" -> curTime,
        "lang" -> "zh_CN"
      )
      val postData = Json.obj(
        "BaseRequest" -> Json.obj(
          "Uin" -> userInfo.wxuin,
          "Sid" -> userInfo.wxsid,
          "Skey" -> userInfo.skey,
          "DeviceID" -> userInfo.deviceId
        ),
        "Count" -> count,
        "List" -> userList
      )

      httpUtil.postJsonRequestSend("get group contact", baseUrl, params, postData, cookies).map { js =>
        try {
          val ret = (js \ "BaseResponse" \ "Ret").as[Int]
          if (ret == 0) {
//            log.debug("调试"+js)//EncryChatRoomId 盖世英雄 @88e87878df40cc8b362bce037fd4c6ec 5c7ab97adc76df4de260610829dcebcb 669644033
            val contactList = (js \ "ContactList").as[Seq[JsValue]]
            log.info("成功获取到新群组信息，群组数量："+contactList.length
            )
            contactList.par.foreach { groups =>
              val groupName = (groups \ "UserName").asOpt[String].getOrElse("")
              val groupNickName = if((groups \ "NickName").asOpt[String].getOrElse("").equals("")) "未命名群组" else (groups \ "NickName").asOpt[String].getOrElse("")
              val groupImg = (groups \ "HeadImgUrl").asOpt[String].getOrElse("")
              val memberCount = (groups \ "MemberCount").as[Int]
//              val memberList = (groups \ "MemberList").as[Seq[JsValue]]
//              userInfo.GroupList.put(groupName,BaseInfo(groupNickName,"",groupImg,"","",0))
//
//              val memberMap = new ConcurrentHashMap[String,BaseInfo]()
//              memberList.par.foreach { members =>
//                val memUserName = (members \ "UserName").asOpt[String].getOrElse("")
//                val memNickName = (members \ "NickName").asOpt[String].getOrElse("")
//                val memImg = (groups \ "HeadImgUrl").asOpt[String].getOrElse("")
//                val memDisplayName = (members \ "DisplayName").asOpt[String].getOrElse("")
//                val province = (members \ "Province").asOpt[String].getOrElse("")
//                val city = (members \ "City").asOpt[String].getOrElse("")
//                val sex = (members \ "Sex").asOpt[Int].getOrElse(0)
//                memberMap.put(memUserName,BaseInfo(memNickName,memDisplayName,memImg,province,city,sex))
//              }
//              userInfo.GroupMemeberList.put(groupName,memberMap)
              //数据库新增群组信息
//              if (groupName.startsWith("@@")) {
                groupDao.createrGroup(groupName, groupNickName, groupImg, 0, userInfo.userid, memberCount).map { groupid =>
                  if (groupid > 0L) {
                    val memberList = (groups \ "MemberList").as[Seq[JsValue]]
                    val memListLen = memberList.length
                    log.info(s"数据库新增群：$groupNickName 成员数量：$memListLen")
                    val seqInfo = memberList.map { members =>
                      val memUserName = (members \ "UserName").as[String]
                      val memNickName = (members \ "NickName").as[String]
                      val memDisplayName = (members \ "DisplayName").as[String]
                      //数据库新增成员信息
                      (memUserName,memNickName,memDisplayName,groupid)
                    }
                    memberDao.batchCreaterMember(seqInfo)  //批量插入成员数据
//                    memberDao.createrMember(memUserName, memNickName, memDisplayName, groupid)
                  }
                }
//              }
            }

//            self ! SyncCheck()
          }
          else {
            log.error(s"get group contact error:" + js)
          }
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error:" + js + s"ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("get group contact with EXCEPTION：" + e.getMessage)
      }
    case GetContect(seq) => // 获取联系人信息
      log.info(s"开始获取联系人信息 seq=$seq")
      val curTime = System.currentTimeMillis().toString
      val baseUrl = s"http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxgetcontact"

      val cookies = userInfo.cookie
      val params = List(
        "lang" -> "zh_CN",
        "pass_ticket" -> userInfo.pass_ticket,
        "r" -> curTime,
        "seq" -> seq,
        "skey" -> userInfo.skey
      )

      httpUtil.postJsonRequestSend("getContect", baseUrl, params,null,cookies).map { js =>
        try {
          log.debug("开始获取联系人信息")

          val seq = (js \ "Seq").as[Int]
          if(seq != 0){
            self ! GetContect(seq.toString)
          }
          
          val memberList = (js \ "MemberList").as[Seq[JsValue]]
          saveContactInfo(memberList) // 持久化通讯录信息
          self ! SyncCheck()
          
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error:" + js + s"ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("get contact with EXCEPTION：" + e.getMessage)
      }
    case StatusNotify() => //异步通知
      log.info("回复微信异步通知")
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxstatusnotify"

      val cookies = userInfo.cookie
      val curTime = System.currentTimeMillis().toString
      val params = List(
        "pass_ticket" -> userInfo.pass_ticket,
        "lang" -> "zh_CN"
      )
      val postData = Json.obj(
        "BaseRequest" -> Json.obj(
          "Uin" -> userInfo.wxuin,
          "Sid" -> userInfo.wxsid,
          "Skey" -> userInfo.skey,
          "DeviceID" -> userInfo.deviceId //2-17位随机数字
        ),
        "Code" -> "3",
        "FromUserName" -> userInfo.username,
        "ToUserName" -> userInfo.username,
        "ClientMsgId" -> curTime
      )

      httpUtil.postJsonRequestSend("webwxstatusnotify", baseUrl, params, postData, cookies).map { js =>
        try {
          log.debug("webwxstatusnotify:" + js)
//          self ! GetContect("0")//Todo debug
          self ! SyncCheck()
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error: $js ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("webwxstatusnotify with EXCEPTION：" + e.getMessage)
      }
    case WXInit() => //初始化
      log.info("开始微信初始化")
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxinit"

      val cookies = userInfo.cookie
      val curTime = System.currentTimeMillis()

      val params = List(
        "pass_ticket" -> userInfo.pass_ticket,
        "r" -> (~curTime.toInt).toString,
        "lang" -> "zh_CN"
      )
      val postData = Json.obj(
        "BaseRequest" -> Json.obj(
          "Uin" -> userInfo.wxuin,
          "Sid" -> userInfo.wxsid,
          "Skey" -> userInfo.skey,
          "DeviceID" -> userInfo.deviceId //2-17位随机数字
        )
      )

      httpUtil.postJsonRequestSend("weixin init", baseUrl, params, postData, cookies).map { js =>
        try {
//          log.debug("weixin init res:" + js)
          val ret = (js \ "BaseResponse" \ "Ret").as[Int]
//          log.debug("ret:" + ret)
          if (ret == 0) {
            val count = (js \ "Count").as[Int]
            val username = (js \ "User" \ "UserName").as[String]
            val userList = js \ "ContactList" \\ "UserName"

            //Todo 这里获取最近联系的人的信息，如果用户发现获取不到要设置的群，可以在群里发一条消息，然后再调用这个接口获取一次最近的联系人信息
            val chatSet = (js \ "ChatSet").as[String].split(",") // chatset是最近联系人或群的id，之后调用GetGroupContect获取这些群的详细信息
            val Synckey = (js \ "SyncKey").as[JsObject]
            var synckey = createSyncKey(Synckey)

            userInfo.username = username
            userInfo.SyncKey = Synckey
            userInfo.synckey = synckey
            userInfo.chatset = chatSet
            self ! StatusNotify()
          }
          else {
            val errMsg = (js \ "BaseResponse" \ "ErrMsg").as[String]
            log.error(s"weixin init error:ret:$ret errormsg:$errMsg" )
          }

        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"weixin init error:$js ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("weixin init with EXCEPTION：" + e.getMessage)
      }
    case GetTicketAndKey() => // 获取ticket
      log.info("开始获取Tickey和Key")
      val baseUrl = "http://"+userInfo.base_uri+"cgi-bin/mmwebwx-bin/webwxnewloginpage"

      val curTime = System.currentTimeMillis().toString
      val params = List(
        "ticket" -> userInfo.ticket,
        "uuid" -> userInfo.uuid,
        "lang" -> "zh_CN",
        "scan" -> userInfo.scan,
        "fun" -> "new",
        "version" -> "v2"
      )
      httpUtil.getXMLRequestSend("get Ticket And Key", baseUrl, params).map { res =>
        try {
          val xml = res._1
          val cookies = res._2
          val cookie = cookies.map(m => m.name.get + "=" + m.value.get).mkString(";")
          log.debug("get request cookies:" + cookie)
          val ret = xml \\ "error" \\ "ret" text
          val message = xml \\ "error" \\ "message" text


          if (ret == "0") {
            val skey = xml \\ "error" \\ "skey" text
            val wxsid = xml \\ "error" \\ "wxsid" text
            val wxuin = xml \\ "error" \\ "wxuin" text
            val pass_ticket = xml \\ "error" \\ "pass_ticket" text
            val isgrayscale = xml \\ "error" \\ "isgrayscale" text

            userInfo.skey = skey
            userInfo.wxsid = wxsid
            userInfo.wxuin = wxuin
            userInfo.pass_ticket = pass_ticket
            userInfo.cookie = cookie
            self ! WXInit()
          }
          else {
            log.error(s"get Ticket And Key error:$message")
          }
        } catch {
          case ex: Throwable =>
            ex.printStackTrace()
            log.error(s"error:" + res._1 + s"ex: $ex")
        }
      }.onFailure {
        case e: Exception =>
          log.error("get tickey and key with EXCEPTION：" + e.getMessage)
      }

  }
}
