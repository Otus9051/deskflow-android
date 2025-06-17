/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import java.io.File
import java.nio.file.Path

fun which(executableName: String): Path {
  val pathEnv = System.getenv("PATH") ?: throw Error("PATH is not defined")  // 1. Access PATH
  val pathSeparator = if (System.getProperty("os.name").startsWith("Windows")) ";" else ":" // Determine the separator
  val pathDirs = pathEnv.split(pathSeparator) // 2. Split PATH

  for (dir in pathDirs) { // 3. Iterate and Check
    val executableFile = File(dir, executableName)
    if (executableFile.exists() && executableFile.canExecute()) {
      return executableFile.toPath()  // Found, return the file
    }
    val executableFileWithExtension = File(dir, "$executableName.exe") // check windows extension
    if (executableFileWithExtension.exists() && executableFileWithExtension.canExecute()) {
      return executableFileWithExtension.toPath()
    }
  }
  throw Error("Executable $executableName not found in $pathDirs")
  //return null // Not found in any PATH directory
}