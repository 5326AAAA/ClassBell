package cn.classbell.app

object FailureMessages {
    fun describe(error: Exception): String = when (error) {
        is ApiProblem -> error.message ?: "学习通暂未完成此次操作，请使用官方网页登录。"
        is java.net.UnknownHostException -> "无法解析学习通网址，请检查网络或 DNS；这与通知权限无关。"
        is java.net.SocketTimeoutException -> "连接学习通超时，请重试或使用官方网页登录。"
        is javax.net.ssl.SSLException -> "与学习通的安全连接失败，请检查手机日期、网络或使用官方网页登录。"
        is java.net.ConnectException -> "未能连接学习通服务器，请检查网络后重试。"
        is java.security.GeneralSecurityException -> "本机加密存储暂不可用，请重新打开 App 后重试。"
        is java.io.IOException -> "连接中断，请重试或使用官方网页登录。"
        else -> "此次操作未完成，请重试或使用官方网页登录。登录不需要通知权限。"
    }
}
