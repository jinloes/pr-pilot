package com.jinloes.prpilot.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class UrlEncodeTest {

    @Test fun plainTextUnchanged() = assertEquals("hello", urlEncode("hello"))

    @Test fun spaceEncoded() = assertFalse(urlEncode("hello world").contains(" "))

    @Test fun ampersandEncoded() = assertFalse(urlEncode("a&b").contains("&"))

    @Test fun slashEncoded() = assertFalse(urlEncode("owner/repo").contains("/"))

    @Test fun equalsEncoded() = assertFalse(urlEncode("a=b").contains("="))
}
