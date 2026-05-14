package com.jinloes.prpilot.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ProcessUtilKmpTest : FunSpec({
    context("findBinary") {
        test("empty list returns fallback") {
            ProcessUtil.findBinary("mybinary", emptyList()) shouldBe "mybinary"
        }
        test("no candidate exists returns fallback") {
            ProcessUtil.findBinary("mybinary", listOf("/no/such/path", "/another/missing")) shouldBe "mybinary"
        }
    }
})
