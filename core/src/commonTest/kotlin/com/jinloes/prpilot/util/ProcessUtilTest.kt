package com.jinloes.prpilot.util

import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessUtilTest {

    @Test fun emptyListReturnsFallback() =
        assertEquals("mybinary", ProcessUtil.findBinary("mybinary", emptyList()))

    @Test fun noCandidateExistsReturnsFallback() =
        assertEquals(
            "mybinary",
            ProcessUtil.findBinary("mybinary", listOf("/no/such/path", "/another/missing")),
        )
}
