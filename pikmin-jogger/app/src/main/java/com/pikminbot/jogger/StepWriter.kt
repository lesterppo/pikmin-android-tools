package com.pikminbot.jogger

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
 * Health Connect step writer for the jogger.
 *
 * Unlike the HC Step Injector (which back-fills chunks), the jogger APPENDS
 * small records continuously while jogging: 2 steps/s accumulated and flushed
 * every 30 s. All records carry the `pikminjogger-` clientRecordId prefix so
 * they can be verified / deleted independently of the game's own records.
 */
object StepWriter {
    private const val TAG = "PikminJogger"
    const val PREFIX = "pikminjogger-"
    const val REQ_PERMISSIONS = 1001

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

    /** Append one record of [steps] steps spanning [start]..[end]. Returns a short result string. */
    fun append(ctx: Context, start: Instant, end: Instant, steps: Long): String {
        return try {
            runBlocking {
                val status = HealthConnectClient.getSdkStatus(ctx)
                if (status != HealthConnectClient.SDK_AVAILABLE) {
                    return@runBlocking "ERR: HC not available (status=$status)"
                }
                val client = HealthConnectClient.getOrCreate(ctx)
                val granted = client.permissionController.getGrantedPermissions()
                if (HealthPermission.getWritePermission(StepsRecord::class) !in granted) {
                    return@runBlocking "ERR: WRITE_STEPS not granted"
                }
                if (end <= start || steps <= 0) return@runBlocking "ERR: bad window"
                val off = ZoneOffset.systemDefault().rules.getOffset(end)
                val device = Device(Device.TYPE_PHONE, "pikminbot", "jogger")
                val record = StepsRecord(
                    start, off, end, off, steps,
                    Metadata.activelyRecorded(
                        device = device,
                        clientRecordId = "$PREFIX${start.toEpochMilli()}-${UUID.randomUUID().toString().substring(0, 4)}"
                    )
                )
                val resp = client.insertRecords(listOf(record))
                "OK $steps steps [${resp.recordIdsList.size} id]"
            }
        } catch (e: Exception) {
            Log.e(TAG, "append failed", e)
            "ERR: ${e.message}"
        }
    }

    /** Read back the records THIS app wrote between [from] and [to]. */
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
                    .map { Chunk(it.startTime, it.endTime, it.count, it.metadata.id, it.metadata.clientRecordId) }
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
