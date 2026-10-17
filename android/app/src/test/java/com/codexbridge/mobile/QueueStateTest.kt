package com.codexbridge.mobile

import com.codexbridge.mobile.data.QueueItem
import com.codexbridge.mobile.data.ThreadDetailResponse
import com.codexbridge.mobile.data.applyQueueSnapshot
import com.codexbridge.mobile.data.applyQueueUpdate
import com.codexbridge.mobile.data.decodeStreamEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class QueueStateTest {
    @Test
    fun snapshotReplacesTheVisibleThreadQueue() {
        val current = listOf(
            QueueItem(queue_id = "old", thread_id = "t1", text = "旧项", status = "pending"),
        )
        val snapshot = listOf(
            QueueItem(queue_id = "q1", thread_id = "t1", text = "第一项", status = "pending", revision = 2),
            QueueItem(queue_id = "q2", thread_id = "t1", text = "第二项", status = "pending", revision = 1),
        )

        assertEquals(snapshot, applyQueueSnapshot(current, "t1", snapshot))
        assertEquals(current, applyQueueSnapshot(current, "other", snapshot))
    }

    @Test
    fun updateUpsertsAndRemovesTerminalItems() {
        val current = listOf(
            QueueItem(queue_id = "q1", thread_id = "t1", text = "第一项", status = "pending", revision = 1),
        )
        val updated = QueueItem(
            queue_id = "q1",
            thread_id = "t1",
            text = "改后的文字",
            status = "pending",
            revision = 2,
        )

        assertEquals(
            listOf(updated),
            applyQueueUpdate(current, "t1", updated, removed = false),
        )
        assertEquals(
            emptyList<QueueItem>(),
            applyQueueUpdate(listOf(updated), "t1", updated, removed = true),
        )
    }

    @Test
    fun otherThreadsAreNotTouched() {
        val mine = QueueItem(queue_id = "q1", thread_id = "t1", text = "第一项")
        val other = QueueItem(queue_id = "q2", thread_id = "t2", text = "另一项")

        val result = applyQueueUpdate(listOf(mine, other), "t1", other, removed = true)

        assertEquals(listOf(mine, other), result)
    }

    @Test
    fun decodesAuthoritativeQueueSnapshotFromBridgePayload() {
        val raw = """
            {"type":"queue_snapshot","thread_id":"t1","revision":7,"paused":false,
             "server_time":1770000000.5,
             "items":[
               {"queue_id":"q1","thread_id":"t1","text":"第一项","model":"","effort":"",
                "status":"pending","revision":1,"created_at":1770000000.5,
                "updated_at":1770000000.5,"created_by":"phone-1","created_by_kind":"phone","error":""}
             ]}
        """.trimIndent()

        val event = decodeStreamEvent(raw)

        assertNotNull(event)
        assertEquals("queue_snapshot", event!!.type)
        assertEquals("t1", event.thread_id)
        assertEquals(listOf("q1"), event.queue.map { it.queue_id })
        assertEquals("第一项", event.queue.first().text)
        assertEquals(1L, event.queue.first().revision)
        assertEquals(1770000000.5, event.queue.first().createdAt, 0.0)
    }

    @Test
    fun decodesThreadDetailQueueSnapshot() {
        val raw = """
            {"ok":true,"thread":{"thread_id":"t1"},"task":{"thread_id":"t1"},
             "messages":[],
             "queue":{"thread_id":"t1","revision":7,"paused":false,
               "items":[
                 {"queue_id":"q1","thread_id":"t1","text":"排队中","model":"","effort":"",
                  "status":"pending","revision":1,"created_at":1770000000.5,
                  "updated_at":1770000000.5,"created_by":"phone-1",
                  "created_by_kind":"phone","error":""}
               ]}}
        """.trimIndent()

        val detail = Json { ignoreUnknownKeys = true }
            .decodeFromString(ThreadDetailResponse.serializer(), raw)

        assertEquals(listOf("q1"), detail.queue.items.map { it.queue_id })
        assertEquals(7L, detail.queue.revision)
    }

    @Test
    fun decodesSingleQueueItemFromBridgeItemField() {
        val raw = """
            {"type":"queue_item_updated","thread_id":"t1","queue_id":"q1","revision":9,
             "server_time":1770000000.5,
             "item":{"queue_id":"q1","thread_id":"t1","text":"改后的文字","model":"","effort":"",
                     "status":"failed","revision":3,"created_at":1770000000.5,
                     "updated_at":1770000000.5,"created_by":"phone-1","created_by_kind":"phone",
                     "error":"boom"}}
        """.trimIndent()

        val event = decodeStreamEvent(raw)

        assertNotNull(event)
        assertEquals("q1", event!!.queueId)
        val item = event.queueItem
        assertNotNull(item)
        assertEquals("改后的文字", item!!.text)
        assertEquals("failed", item.status)
        assertEquals(3L, item.revision)
        assertEquals("boom", item.error)
    }

    @Test
    fun leavesMessageItemEventsIntact() {
        val raw = """
            {"type":"item_completed","thread_id":"t1","item_id":"i1",
             "item":{"id":"i1","kind":"agent","text":"完成","detail":{},
                     "turn_id":"turn1","created_at":1770000000}}
        """.trimIndent()

        val event = decodeStreamEvent(raw)

        assertNotNull(event)
        assertEquals("item_completed", event!!.type)
        assertEquals("i1", event.item?.id)
        assertEquals("完成", event.item?.text)
        assertEquals(null, event.queueItem)
    }
}
