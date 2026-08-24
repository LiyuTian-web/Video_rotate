package com.losslessrotate.video.job

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class RotationJobStore(private val context: Context) {
    private val jobsDirectory: File
        get() = File(context.filesDir, "rotation_jobs").apply { mkdirs() }

    fun write(spec: RotationJobSpec): String {
        cleanupOldJobs()
        val id = UUID.randomUUID().toString()
        File(jobsDirectory, "$id.json").writeText(spec.toJson().toString(), Charsets.UTF_8)
        return id
    }

    fun read(id: String): RotationJobSpec {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "任务编号无效" }
        val file = File(jobsDirectory, "$id.json")
        require(file.isFile) { "任务文件不存在" }
        return JSONObject(file.readText(Charsets.UTF_8)).toSpec()
    }

    fun delete(id: String) {
        if (id.matches(Regex("[0-9a-fA-F-]{36}"))) File(jobsDirectory, "$id.json").delete()
    }

    private fun cleanupOldJobs() {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        jobsDirectory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}

private fun RotationJobSpec.toJson(): JSONObject = JSONObject().apply {
    put("angle", angleDegrees)
    put("output", JSONObject().apply {
        when (val target = outputTarget) {
            OutputTarget.AdjacentRotateFolders -> put("type", "adjacent")
            is OutputTarget.SafDirectory -> {
                put("type", "saf")
                put("uri", target.uri)
            }
        }
    })
    put("inputs", JSONArray().apply {
        inputs.forEach { input ->
            put(JSONObject().apply {
                put("uri", input.sourceUri)
                put("name", input.displayName)
                put("mime", input.mimeType)
                put("size", input.size)
                putOpt("path", input.relativePath)
                putOpt("volume", input.volumeName)
                putOpt("tree", input.sourceTreeUri)
                putOpt("data", input.legacyDataPath)
            })
        }
    })
}

private fun JSONObject.toSpec(): RotationJobSpec {
    val outputJson = getJSONObject("output")
    val output = when (outputJson.getString("type")) {
        "adjacent" -> OutputTarget.AdjacentRotateFolders
        "saf" -> OutputTarget.SafDirectory(outputJson.getString("uri"))
        else -> error("未知输出方式")
    }
    val array = getJSONArray("inputs")
    val inputs = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                RotationInput(
                    sourceUri = item.getString("uri"),
                    displayName = item.getString("name"),
                    mimeType = item.getString("mime"),
                    size = item.getLong("size"),
                    relativePath = item.optString("path").takeIf { it.isNotBlank() },
                    volumeName = item.optString("volume").takeIf { it.isNotBlank() },
                    sourceTreeUri = item.optString("tree").takeIf { it.isNotBlank() },
                    legacyDataPath = item.optString("data").takeIf { it.isNotBlank() },
                ),
            )
        }
    }
    return RotationJobSpec(inputs, getInt("angle"), output)
}
