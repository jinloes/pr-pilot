package com.jinloes.prpilot.services

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class UrlEncodeTest : FunSpec({
    context("urlEncode") {
        test("plain text unchanged") { urlEncode("hello") shouldBe "hello" }
        test("space encoded") { urlEncode("hello world") shouldNotContain " " }
        test("ampersand encoded") { urlEncode("a&b") shouldNotContain "&" }
        test("slash encoded") { urlEncode("owner/repo") shouldNotContain "/" }
        test("equals encoded") { urlEncode("a=b") shouldNotContain "=" }
    }
})
