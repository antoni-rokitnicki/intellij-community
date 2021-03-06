/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vcs

import com.intellij.diff.comparison.iterables.DiffIterableUtil
import com.intellij.diff.comparison.iterables.DiffIterableUtil.fair
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Comparing
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.ex.*
import com.intellij.openapi.vcs.ex.LineStatusTracker.Mode
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.LightPlatformTestCase.assertOrderedEquals
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.containers.ContainerUtil
import java.util.*

private typealias DiffRange = com.intellij.diff.util.Range

abstract class BaseLineStatusTrackerTestCase : LightPlatformTestCase() {
  protected fun test(text: String, task: Test.() -> Unit) {
    test(text, text, false, task)
  }

  protected fun test(text: String, vcsText: String, smart: Boolean = false, task: Test.() -> Unit) {
    val file = LightVirtualFile("LSTTestFile", PlainTextFileType.INSTANCE, parseInput(text))
    val document = FileDocumentManager.getInstance().getDocument(file)!!
    val tracker = runWriteAction {
      val tracker = SimpleLocalLineStatusTracker.createTracker(getProject(), document, file, if (smart) Mode.SMART else Mode.DEFAULT)
      tracker.setBaseRevision(parseInput(vcsText))
      tracker
    }

    try {
      val test = Test(tracker)
      test.verify()

      task(test)

      test.verify()
    }
    finally {
      tracker.release()
    }
  }

  protected class Test(val tracker: LineStatusTracker<*>) {
    val file: VirtualFile = tracker.virtualFile
    val document: Document = tracker.document
    val vcsDocument: Document = tracker.vcsDocument

    fun assertRangesEmpty() {
      assertRanges()
    }

    fun assertRanges(vararg expected: Range) {
      assertEqualRanges(expected.toList(), tracker.getRanges()!!)
    }


    fun insertAtStart(text: String) {
      runCommand { document.insertString(0, parseInput(text)) }
    }

    fun TestRange.insertBefore(text: String) {
      runCommand { document.insertString(this.start, parseInput(text)) }
    }

    fun TestRange.insertAfter(text: String) {
      runCommand { document.insertString(this.end, parseInput(text)) }
    }

    fun TestRange.delete() {
      runCommand { document.deleteString(this.start, this.end) }
    }

    fun TestRange.replace(text: String) {
      runCommand { document.replaceString(this.start, this.end, parseInput(text)) }
    }

    fun replaceWholeText(text: String) {
      runCommand { document.replaceString(0, document.textLength, parseInput(text)) }
    }

    fun stripTrailingSpaces() {
      (document as DocumentImpl).stripTrailingSpaces(null, true)
    }

    fun rollbackLine(line: Int) {
      val lines = BitSet()
      lines.set(line)
      rollbackLines(lines)
    }

    fun rollbackLines(lines: BitSet) {
      runCommand {
        tracker.rollbackChanges(lines)
      }
    }


    class TestRange(val start: Int, val end: Int)


    operator fun Int.not(): Helper = Helper(this)
    operator fun Helper.minus(end: Int): TestRange = TestRange(this.start, end)
    class Helper(val start: Int)

    infix fun String.at(range: TestRange): TestRange {
      assertEquals(parseInput(this), document.charsSequence.subSequence(range.start, range.end).toString())
      return range
    }
    infix fun Int.th(text: String): TestRange = findPattern(text, this - 1)

    fun String.insertBefore(text: String) {
      findPattern(this).insertBefore(text)
    }

    fun String.insertAfter(text: String) {
      findPattern(this).insertAfter(text)
    }

    fun String.delete() {
      findPattern(this).delete()
    }

    fun String.replace(text: String) {
      findPattern(this).replace(text)
    }

    private fun findPattern(text: String): TestRange {
      val pattern = parseInput(text)
      val firstOffset = document.immutableCharSequence.indexOf(pattern)
      val lastOffset = document.immutableCharSequence.lastIndexOf(pattern)
      assertTrue(firstOffset == lastOffset && firstOffset != -1)
      return TestRange(firstOffset, firstOffset + pattern.length)
    }

    private fun findPattern(text: String, index: Int): TestRange {
      val pattern = parseInput(text)
      assertTrue(index >= 0)

      var offset = -1
      for (i in 0..index) {
        val newOffset = document.immutableCharSequence.indexOf(pattern, offset + 1)
        assertTrue(newOffset >= 0 && (offset == -1 || newOffset >= offset + pattern.length))
        offset = newOffset
      }
      return TestRange(offset, offset + text.length)
    }

    fun runCommand(task: () -> Unit) {
      CommandProcessor.getInstance().executeCommand(getProject(), {
        ApplicationManager.getApplication().runWriteAction(task)
      }, "", null)
      verify()
    }


    fun range(): Range {
      return tracker.getRanges()!!.single()
    }

    fun range(index: Int): Range {
      return tracker.getRanges()!![index]
    }

    fun Range.assertVcsContent(text: String) {
      val actual = DiffUtil.getLinesContent(tracker.vcsDocument, this.vcsLine1, this.vcsLine2).toString()
      assertEquals(parseInput(text), actual)
    }

    fun Range.assertType(type: Byte) {
      assertEquals(type,  this.type)
    }

    fun Range.rollback() {
      runCommand {
        tracker.rollbackChanges(this)
      }
    }


    fun compareRanges() {
      val expected = createRanges(document, vcsDocument)
      val actual = tracker.getRanges()!!
      assertEqualRanges(expected, actual)
    }

    fun assertTextContentIs(expected: String) {
      assertEquals(parseInput(expected), document.text)
    }


    fun verify() {
      checkValid()
      checkCantTrim()
      checkCantMerge()
      checkInnerRanges()
    }

    private fun checkValid() {
      val diffRanges = tracker.getRanges()!!.map { DiffRange(it.vcsLine1, it.vcsLine2, it.line1, it.line2) }
      val iterable = fair(DiffIterableUtil.create(diffRanges, DiffUtil.getLineCount(vcsDocument), DiffUtil.getLineCount(document)))

      for (range in iterable.iterateUnchanged()) {
        val lines1 = DiffUtil.getLines(vcsDocument, range.start1, range.end1)
        val lines2 = DiffUtil.getLines(document, range.start2, range.end2)
        assertOrderedEquals(lines1, lines2)
      }
    }

    private fun checkCantTrim() {
      val ranges = tracker.getRanges()!!
      for (range in ranges) {
        if (range.type != Range.MODIFIED) continue

        val lines1 = DiffUtil.getLines(vcsDocument, range.vcsLine1, range.vcsLine2)
        val lines2 = DiffUtil.getLines(document, range.line1, range.line2)

        val f1 = ContainerUtil.getFirstItem(lines1)
        val f2 = ContainerUtil.getFirstItem(lines2)

        val l1 = ContainerUtil.getLastItem(lines1)
        val l2 = ContainerUtil.getLastItem(lines2)

        assertFalse(Comparing.equal(f1, f2))
        assertFalse(Comparing.equal(l1, l2))
      }
    }

    private fun checkCantMerge() {
      val ranges = tracker.getRanges()!!
      for (i in 0 until ranges.size - 1) {
        assertFalse(ranges[i].line2 == ranges[i + 1].line1)
      }
    }

    private fun checkInnerRanges() {
      val ranges = tracker.getRanges()!!

      for (range in ranges) {
        val innerRanges = range.innerRanges ?: continue
        if (range.type != Range.MODIFIED) {
          assertEmpty(innerRanges)
          continue
        }

        var last = 0
        for (innerRange in innerRanges) {
          assertEquals(innerRange.line1 == innerRange.line2, innerRange.type == Range.DELETED)

          assertEquals(last, innerRange.line1)
          last = innerRange.line2
        }
        assertEquals(last, range.line2 - range.line1)

        val lines1 = DiffUtil.getLines(vcsDocument, range.vcsLine1, range.vcsLine2)
        val lines2 = DiffUtil.getLines(document, range.line1, range.line2)

        var start = 0
        for (innerRange in innerRanges) {
          if (innerRange.type != Range.EQUAL) continue

          for (i in innerRange.line1 until innerRange.line2) {
            val line = lines2[i]
            val searchSpace = lines1.subList(start, lines1.size)
            val index = ContainerUtil.indexOf(searchSpace) { it -> StringUtil.equalsIgnoreWhitespaces(it, line) }
            assertTrue(index != -1)
            start += index + 1
          }
        }
      }
    }
  }


  companion object {
    private fun parseInput(input: String) = input.replace('_', '\n')

    @JvmStatic
    fun assertEqualRanges(expected: List<Range>, actual: List<Range>) {
      assertOrderedEquals("", actual, expected) { r1, r2 ->
        r1.line1 == r2.line1 &&
        r1.line2 == r2.line2 &&
        r1.vcsLine1 == r2.vcsLine1 &&
        r1.vcsLine2 == r2.vcsLine2
      }
    }
  }
}
