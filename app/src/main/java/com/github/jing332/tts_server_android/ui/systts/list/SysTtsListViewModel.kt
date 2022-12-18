package com.github.jing332.tts_server_android.ui.systts.list

import androidx.lifecycle.ViewModel
import com.github.jing332.tts_server_android.App
import com.github.jing332.tts_server_android.constant.ReadAloudTarget
import com.github.jing332.tts_server_android.data.appDb
import com.github.jing332.tts_server_android.data.entities.systts.GroupWithTtsItem
import com.github.jing332.tts_server_android.data.entities.systts.SystemTts
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import tts_server_lib.Tts_server_lib

class SysTtsListViewModel : ViewModel() {
    companion object {
        const val TAG = "TtsConfigFragmentViewModel"
    }

    /**
     * 检查多语音音频格式是否一致
     * @return 采样率是否相等
     */
    fun checkMultiVoiceFormat(list: List<SystemTts>): Boolean {
        val aside =
            list.filter { it.isEnabled && it.readAloudTarget == ReadAloudTarget.ASIDE }.getOrNull(0)
        val dialogue =
            list.filter { it.isEnabled && it.readAloudTarget == ReadAloudTarget.DIALOGUE }
                .getOrNull(0)

        if (aside == null || dialogue == null)
            return true
        else if (aside.isEnabled && dialogue.isEnabled)
            return aside.tts.audioFormat.sampleRate == dialogue.tts.audioFormat.sampleRate

        return true
    }


    /* 按API排序 */
    fun sortListByApi() {
    }

    /* 按朗读目标排序 */
    fun sortListByRaTarget() {
    }

    /* 按显示名称排序 */
    fun sortListByDisplayName() {
    }

    /* 上传配置到URL */
    fun uploadConfigToUrl(json: String): Result<String> {
        return try {
            val url = Tts_server_lib.uploadConfig(json)

            Result.success(url)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /* 导出配置 */
    fun exportConfig(): String {
        return try {
            App.jsonBuilder.encodeToString(appDb.systemTtsDao.getSysTtsWithGroups())
        } catch (e: Exception) {
            "导出失败：${e.message}"
        }
    }

    /* 从URL导入配置 */
    fun importConfigByUrl(url: String): String? {
        val jsonStr = try {
            Tts_server_lib.httpGet(url, "")
        } catch (e: Exception) {
            return e.message
        }
        return importConfig(jsonStr.decodeToString())
    }

    /* 从json导入配置 */
    fun importConfig(json: String): String? {
        try {
            val list = App.jsonBuilder.decodeFromString<List<GroupWithTtsItem>>(json)
            list.forEach {
//                appDb.systemTtsDao.insert(it)
            }
        } catch (e: Exception) {
            return e.message
        }
        return null
    }


}