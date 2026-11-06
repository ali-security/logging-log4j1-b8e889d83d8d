/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.log4j.nt;

import java.io.File;
import java.io.IOException;
import java.util.StringTokenizer;

import junit.framework.TestCase;
import org.apache.log4j.LogManager;


/**
 * Tests for the <code>log4j.nteventlog.dll.path</code> system property of
 * {@link NTEventLogAppender}, which mitigates DLL preloading (hijacking).
 *
 * <p>The NTEventLogAppenderDllPath target in tests/build.xml runs this test on
 * any platform: it plants a bogus NTEventLogAppender library under every name
 * the appender searches for on <code>java.library.path</code>, and starts the
 * JVM with <code>log4j.nteventlog.dll.path</code> set to the absolute path of
 * a native library that is known to load on the running JVM.  Loading any of
 * the planted files fails with an UnsatisfiedLinkError.
 */
public class NTEventLogAppenderDllPathTest extends TestCase {

  private String originalDllPath;

  public void setUp() throws Exception {
    originalDllPath = System.getProperty(NTEventLogAppender.DLL_PATH_PROPERTY);
    // Run NTEventLogAppender's static initializer (which loads the native
    // library) while the property still holds the value this JVM was started
    // with, before the tests below change it.
    try {
      Class.forName("org.apache.log4j.nt.NTEventLogAppender");
    } catch (LinkageError e) {
      fail("NTEventLogAppender did not load the library configured in "
          + NTEventLogAppender.DLL_PATH_PROPERTY + " (" + originalDllPath
          + ") and searched the library path instead: " + e);
    }
    System.getProperties().remove(NTEventLogAppender.DLL_PATH_PROPERTY);
  }

  public void tearDown() {
    if (originalDllPath != null) {
      System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, originalDllPath);
    } else {
      System.getProperties().remove(NTEventLogAppender.DLL_PATH_PROPERTY);
    }
    LogManager.shutdown();
  }

  /**
   * When the system property is not set, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_notSet() {
    System.getProperties().remove(NTEventLogAppender.DLL_PATH_PROPERTY);
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is an empty string, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_emptyString() {
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, "");
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is whitespace only, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_whitespaceOnly() {
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, "   ");
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is a relative path, resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_relativePath() {
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, "relative/path/NTEventLogAppender.dll");
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is an absolute path to a file that does not exist,
   * resolveConfiguredDllPath should return null.
   */
  public void testResolveConfiguredDllPath_absoluteButMissing() {
    String missingPath = new File(System.getProperty("java.io.tmpdir"), "no_such_NTEventLogAppender.dll")
        .getAbsolutePath();
    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, missingPath);
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  /**
   * When the system property is an absolute path to an existing file,
   * resolveConfiguredDllPath should return that absolute path.
   */
  public void testResolveConfiguredDllPath_absoluteAndExists() throws IOException {
    File tempFile = File.createTempFile("NTEventLogAppender", ".dll");
    tempFile.deleteOnExit();
    try {
      String absolutePath = tempFile.getAbsolutePath();
      System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, absolutePath);
      String result = NTEventLogAppender.resolveConfiguredDllPath();
      assertNotNull(result);
      assertEquals(absolutePath, result);
    } finally {
      tempFile.delete();
    }
  }

  /**
   * DLL preloading: a library named like the NTEventLogAppender DLL has been
   * planted in a directory on the library search path, where
   * System.loadLibrary picks it up.  With log4j.nteventlog.dll.path naming a
   * trusted absolute file, the static initializer (run in setUp) must load
   * that file via System.load and never search the library path; loading the
   * planted file would have failed the class initialization.
   */
  public void testPlantedLibraryOnSearchPathIsNotLoaded() {
    assertNotNull("log4j.nteventlog.dll.path must be set for this JVM", originalDllPath);
    assertTrue("log4j.nteventlog.dll.path must name an existing file: " + originalDllPath,
        new File(originalDllPath).isFile());
    String plantedName = System.mapLibraryName("NTEventLogAppender");
    File planted = findOnLibraryPath(plantedName);
    assertNotNull("expected a planted " + plantedName + " on java.library.path", planted);

    // A library path search, as done before the fix, resolves to the planted file.
    try {
      System.loadLibrary("NTEventLogAppender");
      fail("expected loading the planted " + planted.getAbsolutePath() + " to fail");
    } catch (UnsatisfiedLinkError expected) {
    }

    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, originalDllPath);
    String configured = NTEventLogAppender.resolveConfiguredDllPath();
    assertEquals(new File(originalDllPath.trim()).getAbsolutePath(), configured);
    assertFalse(configured.equals(planted.getAbsolutePath()));
  }

  /**
   * A relative log4j.nteventlog.dll.path is resolved against the working
   * directory, which is where a planted DLL is expected to be found, so it
   * must be rejected even when it names an existing file.
   */
  public void testRelativePathToPlantedLibraryIsRejected() throws IOException {
    String plantedName = System.mapLibraryName("NTEventLogAppender");
    File planted = findOnLibraryPath(plantedName);
    assertNotNull("expected a planted " + plantedName + " on java.library.path", planted);
    File workingDir = new File(System.getProperty("user.dir"));
    String plantedPath = planted.getCanonicalPath();
    String workingDirPath = workingDir.getCanonicalPath() + File.separator;
    assertTrue("expected the planted library below " + workingDirPath + ": " + plantedPath,
        plantedPath.startsWith(workingDirPath));
    String relativePath = plantedPath.substring(workingDirPath.length());
    assertTrue("expected " + relativePath + " to exist relative to " + workingDir,
        new File(relativePath).exists());

    System.setProperty(NTEventLogAppender.DLL_PATH_PROPERTY, relativePath);
    assertNull(NTEventLogAppender.resolveConfiguredDllPath());
  }

  private static File findOnLibraryPath(final String fileName) {
    String libraryPath = System.getProperty("java.library.path");
    if (libraryPath == null) {
      return null;
    }
    StringTokenizer tokenizer = new StringTokenizer(libraryPath, File.pathSeparator);
    while (tokenizer.hasMoreTokens()) {
      File candidate = new File(tokenizer.nextToken(), fileName);
      if (candidate.isFile()) {
        return candidate;
      }
    }
    return null;
  }
}
