package dev.local.catleaf.nativeapp.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 历史尽力写入的请求体构造（L07）：null→null、转义、形状与契约 3.2 一致。 */
class HistoryUpsertBodyTest {

    @Test fun `builds valid body with nulls and escaping`() {
        val body = buildUpsertBody(
            "feed-1",
            listOf(
                HistoryUpsert("标题\"引号\"", "g1", null, "2026-09-26T00:00:00Z", "描述\\反斜杠", null, null),
            ),
        )
        assertEquals(
            """{"feedId":"feed-1","items":[{"title":"标题\"引号\"","guid":"g1","link":null,""" +
                """"pubDate":"2026-09-26T00:00:00Z","description":"描述\\反斜杠","content":null,"thumbnail":null}]}""",
            body,
        )
    }

    @Test fun `empty items yields empty array`() {
        val body = buildUpsertBody("f", emptyList())
        assertTrue(body.startsWith("""{"feedId":"f","items":[]}"""))
    }
}
