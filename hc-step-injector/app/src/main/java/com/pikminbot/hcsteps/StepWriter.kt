package com.pikminbot.hcsteps

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Health Connect step injector core.
 *
 * Writes fake step records into Health Connect with:
 *  - batch distribution (count spread over `minutes` in `chunkMinutes` chunks)
 *  - historical start times
 *  - clientRecordId duplicate protection (prefix `hcstepwriter-`)
 *  - read-back verification and deletion of records this app created
 */
object StepWriter {
    private const val TAG = "HCStepWriter"
    const val PREFIX = "hcstepwriter-"
    const val REQ_PERMISSIONS = 1001

    /** Small data class describing one written step chunk. */
    data class Chunk(val start: Instant, val end: Instant, val steps: Long, val recordId: String? = null, val clientRecordId: String? = null)

    fun sdkStatus(ctx: Context): Int = HealthConnectClient.getSdkStatus(ctx)

    fun hasPermissions(ctx: Context): Boolean = runBlocking {
        try {
            val client = HealthConnectClient.getOrCreate(ctx)
            val granted = client.permissionController.getGrantedPermissions()
            granted.contains(HealthPermission.getWritePermission(StepsRecord::class))
        } catch (e: Exception) {
            false
        }
    }

    /** Fire the Health Connect permission screen; result arrives in onActivityResult(REQ_PERMISSIONS). */
    fun requestPermissions(activity: Activity) {
        try {
            val client = HealthConnectClient.getOrCreate(activity)
            val contract = PermissionController.createRequestPermissionResultContract()
            val perms = setOf(
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getWritePermission(StepsRecord::class),
            )
            activity.startActivityForResult(contract.createIntent(activity, perms), REQ_PERMISSIONS)
        } catch (e: Exception) {
            Log.e(TAG, "requestPermissions failed", e)
        }
    }

    /**
     * Inject [count] steps distributed across the [minutes] minutes ending at [end],
     * in chunks of [chunkMinutes] minutes. Historical start = end - minutes.
     * Returns a short human-readable result string.
     */
    fun inject(ctx: Context, count: Int, minutes: Int,
               end: Instant = Instant.now(), chunkMinutes: Int = 15): String {
        return try {
            runBlocking {
                val status = HealthConnectClient.getSdkStatus(ctx)
                if (status != HealthConnectClient.SDK_AVAILABLE) {
                    return@runBlocking "ERR: Health Connect not available (status=$status)"
                }
                val client = HealthConnectClient.getOrCreate(ctx)
                val granted = client.permissionController.getGrantedPermissions()
                if (HealthPermission.getWritePermission(StepsRecord::class) !in granted) {
                    return@runBlocking "ERR: WRITE_STEPS not granted. Granted: $granted"
                }
                val start = end.minusSeconds(minutes * 60L)
                val off = ZoneOffset.systemDefault().rules.getOffset(end)
                val device = Device(Device.TYPE_PHONE, "pikminbot", "stepinjector")
                val batchId = UUID.randomUUID().toString().substring(0, 8)

                // Build chunks: split the interval into chunkMinutes slices, apportion steps
                val totalChunks = maxOf(1, (minutes + chunkMinutes - 1) / chunkMinutes)
                val records = ArrayList<StepsRecord>(totalChunks)
                var remaining = count.toLong()
                for (i in 0 until totalChunks) {
                    val cStart = start.plusSeconds((i * chunkMinutes).toLong() * 60L)
                    val cEnd = minOf(start.plusSeconds(((i + 1) * chunkMinutes).toLong() * 60L), end)
                    if (cEnd <= cStart) break
                    val chunkSteps = if (i == totalChunks - 1) remaining else (count.toLong() / totalChunks)
                    remaining -= chunkSteps
                    val metadata = Metadata.activelyRecorded(
                        device = device,
                        clientRecordId = "$PREFIX$batchId-$i"
                    )
                    records.add(StepsRecord(cStart, off, cEnd, off, chunkSteps, metadata))
                }
                val resp = client.insertRecords(records)
                "OK: inserted ${records.size} chunks, $count steps over $minutes min (${resp.recordIdsList.size} ids, batch=$batchId)"
            }
        } catch (e: Exception) {
            Log.e(TAG, "inject failed", e)
            "ERR: ${e.message}"
        }
    }

    /**
     * Read back step records written by THIS app between [from] and [to].
     * Filters on the clientRecordId prefix so third-party records are ignored.
     */
    fun verify(ctx: Context, from: Instant, to: Instant): List<Chunk> {
        return try {
            runBlocking {
                val client = HealthConnectClient.getOrCreate(ctx)
                val resp = client.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(from, to)
                    )
                )
                resp.records
                    .filter { it.metadata.clientRecordId?.startsWith(PREFIX) == true }
                    .sortedBy { it.startTime }
                    .map {
                        Chunk(it.startTime, it.endTime, it.count, it.metadata.id, it.metadata.clientRecordId)
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "verify failed", e)
            emptyList()
        }
    }

    /** Delete every record this app wrote in [from, to]. Returns number deleted. */
    fun deleteMine(ctx: Context, from: Instant, to: Instant): Int {
        return try {
            runBlocking {
                val client = HealthConnectClient.getOrCreate(ctx)
                val chunks = verify(ctx, from, to)
                val ids = chunks.mapNotNull { it.recordId }
                if (ids.isEmpty()) return@runBlocking 0
                client.deleteRecords(StepsRecord::class, ids, emptyList())
                ids.size
            }
        } catch (e: Exception) {
            Log.e(TAG, "delete failed", e)
            -1
        }
    }
}
