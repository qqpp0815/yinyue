package com.yinyue.player.data.fallback

/**
 * 抖音分享文本解析（纯函数，便于单测）。
 *
 * 用户从抖音 App 复制的通常是**一整段文案**，形如：
 *   "7.43 abc:/ 复制打开抖音，看看【某某的作品】…  https://v.douyin.com/iRNBho6/ 复制此链接…"
 * 其中 `v.douyin.com/xxxx` 是**短链，链接里没有视频 id**，必须先跟随跳转拿到
 * 形如 `https://www.douyin.com/video/7301234567890123456` 的最终地址再取 id。
 * 之前直接在原文里找 15~21 位数字，短链场景必然失败（且容易误匹配文案里的其它数字）。
 */
object DouyinShare {

    /** 分享文案里首个 http(s) 链接；兼容 Markdown 尖括号包裹，并剥掉尾随标点/括号 */
    fun extractUrl(text: String): String? {
        val cleaned = text
            .replace("""<\s*(https?://[^>\s]+)\s*>""".toRegex(), "$1")
            .replace("""\]\(\s*(https?://[^)\s]+)\s*\)""".toRegex(), "")
            .replace("""\[(\s*https?://[^\s\[\]()<>]+/?)\]""".toRegex(), "$1")
        val m = Regex("""https?://[a-zA-Z0-9\-._~:/?#@!$&+,;=%']+""").find(cleaned.trim()) ?: return null
        return m.value.trimEnd('.', ',', '\'', ';')
    }

    /** 是否短链（需要跟随跳转才能拿到 id） */
    fun isShortLink(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        return host.endsWith("v.douyin.com") ||
            host.endsWith("v.kuaishou.com") ||
            (host.contains("douyin.com") && !Regex("""/(?:video|note|slides)/\d+""").containsMatchIn(url))
    }

    /** 从（最终的）URL 中解析视频 id：路径 /video|note|slides/{id}、查询参数 modal_id/aweme_id/item_ids，最后兜底长数字 */
    fun parseId(url: String): String? {
        Regex("""/(?:video|note|slides)/(\d{10,25})""").find(url)?.let { return it.groupValues[1] }
        Regex("""(?:modal_id|aweme_id|item_ids|vid)=(\d{10,25})""").find(url)?.let { return it.groupValues[1] }
        // 仅在没有其它线索时，才在 URL 本体里找长数字（避免把文案里的数字当 id）
        Regex("""(\d{15,25})""").find(url)?.let { return it.groupValues[1] }
        return null
    }

    /**
     * 从整段分享文案提取可读标题：
     * 优先书名号（最可靠的歌名信号），其次取【作者的作品】后面的描述段。
     * 示例：“…看看【短视频学习的作品】# 上热门 # 经典歌曲分享 《梦醒时分》 [链接] …”
     */
    fun extractTitle(text: String): String {
        Regex("""《([^《》]{1,80})》""").find(text)?.let { return it.groupValues[1].trim() }
        val author = Regex("""【([^【】]{1,60})的作品】""").find(text)?.groupValues?.get(1).orEmpty()
        var body = text
        author.takeIf { it.isNotBlank() }?.let { body = body.replace("【${it}的作品】", " ") }
        body = Regex("""复制打开抖音.*?""").replace(body, " ")
        body = Regex("""https?://\S+""").replace(body, " ")
        body = Regex("""[a-zA-Z0-9:/.\-_]+[:/]""").replace(body, " ")
        body = Regex("""[#＃@＠]""").replace(body, " ")
        body = Regex("""\d+(\.\d+)?""").replace(body, " ")
        body = body.replace(Regex("""\s+"""), " ").trim(' ', '-', '—', '，', ',', '。', '.', '/', '|')
        val candidate = body.split(' ').filter { it.length >= 2 }
            .firstOrNull { candidate ->
                val authorKey = author.ifBlank { " " }
                !candidate.contains(authorKey) &&
                    !candidate.contains("复制") && !candidate.contains("打开") &&
                    !candidate.contains("抖音") && !candidate.contains("作品")
            }
        return candidate.orEmpty()
    }
}
