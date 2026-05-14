package com.jinloes.prpilot.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class ProcessUtilJvmTest : FunSpec({

    lateinit var tempDir: File
    beforeTest { tempDir = Files.createTempDirectory("put-test-").toFile() }
    afterTest { tempDir.deleteRecursively() }

    context("findBinary") {

        test("first existing candidate is returned") {
            val bin = File(tempDir, "mybinary").also { it.createNewFile() }
            ProcessUtil.findBinary("fallback", listOf(bin.absolutePath)) shouldBe bin.absolutePath
        }

        test("skips non-existent paths and returns first existing") {
            val bin = File(tempDir, "real").also { it.createNewFile() }
            ProcessUtil.findBinary(
                "fallback",
                listOf("/no/such/path", bin.absolutePath, "/also/missing"),
            ) shouldBe bin.absolutePath
        }

        test("directory is not considered a file — skipped") {
            ProcessUtil.findBinary("fallback", listOf(tempDir.absolutePath)) shouldBe "fallback"
        }
    }
})
