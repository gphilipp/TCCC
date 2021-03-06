/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

package jetbrains.buildServer.buildTriggers.vcs.clearcase;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.util.io.FileUtil;
import jetbrains.buildServer.Used;
import jetbrains.buildServer.buildTriggers.vcs.AbstractVcsPropertiesProcessor;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.serverSide.PropertiesProcessor;
import jetbrains.buildServer.util.MultiMap;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.vcs.*;
import jetbrains.buildServer.vcs.patches.PatchBuilder;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class ClearCaseSupport extends ServerVcsSupport implements VcsPersonalSupport,
                                                                  LabelingSupport, VcsFileContentProvider,
                                                                  CollectChangesByIncludeRules, BuildPatchByIncludeRules, TestConnectionSupport
{
  @NonNls public static final String VIEW_PATH = "view-path";
  @NonNls public static final String CC_VIEW_PATH = "cc-view-path";
  @NonNls public static final String RELATIVE_PATH = "rel-path";
  @NonNls public static final String TYPE = "TYPE";
  @NonNls private static final String UCM = "UCM";
  @NonNls private static final String GLOBAL_LABELS_VOB = "global-labels-vob";
  @NonNls private static final String USE_GLOBAL_LABEL = "use-global-label";

  private static final Logger LOG = Logger.getLogger(ClearCaseSupport.class);
  
  private static final String VOBS = "vobs/";

  @NotNull
  public static ViewPath getViewPath(@NotNull final VcsRoot vcsRoot) throws VcsException {
    final String viewPath = vcsRoot.getProperty(VIEW_PATH);
    if (viewPath != null && viewPath.trim().length() != 0) {
      return getViewPath(viewPath);
    }
    ViewPath path = new ViewPath(vcsRoot.getProperty(CC_VIEW_PATH), vcsRoot.getProperty(RELATIVE_PATH));
    return path;
  }

  @NotNull
  public static ViewPath getViewPath(@NotNull final String viewPath) throws VcsException {
    final String ccViewRoot;
    try {
      ccViewRoot = ClearCaseConnection.getClearCaseViewRoot(viewPath);
    } catch (IOException e) {
      throw new VcsException(e);
    }
    return new ViewPath(ccViewRoot, getRelativePath(new File(ccViewRoot), new File(viewPath)));
  }

  @Nullable
  private static String getRelativePath(@NotNull final File parent, @NotNull final File subFile) throws VcsException {
    final StringBuilder sb = new StringBuilder("");
    File file = subFile;

    boolean first = true;

    while (file != null && !CCPathElement.areFilesEqual(file, parent)) {
      if (!first) {
        sb.insert(0, File.separatorChar);
      }
      else {
        first = false;
      }
      sb.insert(0, file.getName());
      file = file.getParentFile();
    }

    if (file == null) return null;

    return sb.toString();
  }


  public ClearCaseConnection createConnection(final VcsRoot root, final FileRule includeRule) throws VcsException {
    return doCreateConnection(root, includeRule, false);
  }

  public ClearCaseConnection createConnection(final VcsRoot root, final FileRule includeRule, final boolean checkCSChange) throws VcsException {
    return doCreateConnection(root, includeRule, checkCSChange);
  }

  private ClearCaseConnection doCreateConnection(final VcsRoot root, final FileRule includeRule, final boolean checkCSChange) throws VcsException {
    boolean isUCM = root.getProperty(TYPE, UCM).equals(UCM);
    final ViewPath viewPath = getViewPath(root);/*loadRule == null ? getViewPath(root) : getViewPath(root, loadRule);*/
    if (includeRule.getFrom().length() > 0) {
      viewPath.setIncludeRuleFrom(includeRule);
    }
    try {
      return new ClearCaseConnection(viewPath, isUCM, root, checkCSChange);
    } catch (Exception e) {
      throw new VcsException(e);
    }
  }

  private ChangedFilesProcessor createCollectingChangesFileProcessor(final MultiMap<CCModificationKey,
      VcsChange> key2changes, final ClearCaseConnection connection) {
    return new MyChangedFilesProcessor(key2changes, connection);
  }


  @Nullable
  private CCModificationKey findKey(final CCModificationKey modificationKey, final MultiMap<CCModificationKey, VcsChange> key2changes) {
    for (CCModificationKey key : key2changes.keySet()) {
      if (key.equals(modificationKey)) return key;
    }
    return null;
  }

  private VcsChange createChange(final VcsChangeInfo.Type type,
                                 final String beforeVersion,
                                 final String afterVersion,
                                 final String childFullPath) throws VcsException {
    return new VcsChange(type, childFullPath, childFullPath, beforeVersion, afterVersion);
  }

  public void buildPatch(VcsRoot root, String fromVersion, String toVersion, PatchBuilder builder, final IncludeRule includeRule) throws IOException, VcsException {
      buildPatchForConnection(builder, fromVersion, toVersion, createConnection(root, includeRule, true));
  }

  private void buildPatchForConnection(PatchBuilder builder, String fromVersion, String toVersion, ClearCaseConnection connection) throws IOException, VcsException {
    try {
      new CCPatchProvider(connection).buildPatch(builder, fromVersion, toVersion);
    } catch (ExecutionException e) {
      throw new VcsException(e);
    } catch (ParseException e) {
      throw new VcsException(e);
    } finally {
      connection.dispose();
    }
  }

  @NotNull
  public byte[] getContent(@NotNull final VcsModification vcsModification,
                           @NotNull final VcsChangeInfo change,
                           @NotNull final VcsChangeInfo.ContentType contentType,
                           @NotNull final VcsRoot vcsRoot) throws VcsException {
    final ClearCaseConnection connection = createConnection(vcsRoot, IncludeRule.createDefaultInstance());
    if (new File (connection.getViewWholePath() + File.separator + change.getRelativeFileName()).exists()) {
      final String filePathWithVersion = new File(connection.getViewWholePath() + File.separator + change.getRelativeFileName() + CCParseUtil.CC_VERSION_SEPARATOR + (
          contentType == VcsChangeInfo.ContentType.BEFORE_CHANGE
              ? change.getBeforeChangeRevisionNumber()
              : change.getAfterChangeRevisionNumber())).toString();
      return getFileContent(connection, filePathWithVersion);
    }
    else {
      return getFileContentInViewAsOfVersion(change.getRelativeFileName(), vcsRoot, vcsModification.getVersion(), connection);
    }

  }

  private byte[] getFileContent(final ClearCaseConnection connection, final String filePath) throws VcsException {
    try {
      final File tempFile = FileUtil.createTempFile("cc", "tmp");
      FileUtil.delete(tempFile);

      try {

        connection.loadFileContent(tempFile, filePath);
        if (tempFile.isFile()) {
          return FileUtil.loadFileBytes(tempFile);
        } else {
          throw new VcsException("Cannot get content of " + filePath);
        }
      } finally {
        FileUtil.delete(tempFile);
      }

    } catch (ExecutionException e) {
      throw new VcsException(e);
    } catch (InterruptedException e) {
      throw new VcsException(e);
    } catch (IOException e) {
      throw new VcsException(e);
    }
  }

  @NotNull
  public byte[] getContent(@NotNull final String filePath, @NotNull final VcsRoot versionedRoot, @NotNull final String version) throws VcsException {
    Loggers.VCS.info("filePath=" + filePath + ", versionedRoot=" + versionedRoot + ", version=" + version);
    ClearCaseConnection connection = createConnection(versionedRoot, IncludeRule.createDefaultInstance());
    return getFileContentInViewAsOfVersion(filePath, versionedRoot, version, connection);
  }

  private byte[] getFileContentInViewAsOfVersion(String filePath, VcsRoot versionedRoot, String version, ClearCaseConnection connection) throws VcsException {
    String dynViewTag = null;
    try {
      dynViewTag = connection.createDynamicViewAtDate(version);
      String dynamicViewDirectory = connection.getDynamicViewDirectory(dynViewTag);
      File file = new File(dynamicViewDirectory + File.separator + connection.getViewPath().getVob() +File.separator + filePath);
      byte[] content = new byte[(int) file.length()];
      FileInputStream fis = null;
      try {
        fis = new FileInputStream(file);
        fis.read(content);
      } finally {
        if (fis != null) {
          fis.close();
        }
      }
      return content;
    } catch (IOException e) {
      throw new VcsException(String.format("Unable to get content for : filePath=%s, versionedRoot=%s, version=%s", filePath, versionedRoot, version),e);
    } catch (ParseException e) {
      throw new VcsException(String.format("Unable to get content for : filePath=%s, versionedRoot=%s, version=%s", filePath, versionedRoot, version),e);
    }finally {
      connection.removeView(dynViewTag);
    }
  }

  @NotNull
  public String getName() {
    return "clearcase";
  }

  @NotNull
  @Used("jsp")
  public String getDisplayName() {
    return "ClearCase";
  }

  @NotNull
  public PropertiesProcessor getVcsPropertiesProcessor() {
    return new AbstractVcsPropertiesProcessor() {
      public Collection<InvalidProperty> process(Map<String, String> properties) {

        final List<InvalidProperty> result = new ArrayList<InvalidProperty>();
        if (isEmpty(properties.get(ClearCaseSupport.CC_VIEW_PATH))) {
          result.add(new InvalidProperty(ClearCaseSupport.CC_VIEW_PATH, "ClearCase view path must be specified"));
        } else {
          try {
            CCPathElement.normalizePath(ClearCaseSupport.CC_VIEW_PATH);
            CCPathElement.normalizePath(ClearCaseSupport.RELATIVE_PATH);
          } catch (VcsException e) {
            result.add(new InvalidProperty(ClearCaseSupport.CC_VIEW_PATH, e.getLocalizedMessage()));
            return result;
          }

          final int countBefore = result.size();
          checkDirectoryProperty(ClearCaseSupport.CC_VIEW_PATH, properties.get(ClearCaseSupport.CC_VIEW_PATH), result);

/*
          if (result.size() == countBefore) {
            try {
              checkClearCaseView(ClearCaseSupport.CC_VIEW_PATH, properties.get(ClearCaseSupport.CC_VIEW_PATH), result);
            } catch (VcsException e) {
              result.add(new InvalidProperty(ClearCaseSupport.CC_VIEW_PATH, e.getLocalizedMessage()));
            } catch (IOException e) {
              result.add(new InvalidProperty(ClearCaseSupport.CC_VIEW_PATH, e.getLocalizedMessage()));
            }
          }
*/

          try {
            if (isEmpty(CCPathElement.normalizePath(properties.get(ClearCaseSupport.RELATIVE_PATH)))) {
              result.add(new InvalidProperty(ClearCaseSupport.RELATIVE_PATH, "Relative path must not be equal to \".\". At least VOB name must be specified."));
            } else {
              final ViewPath viewPath = new ViewPath(properties.get(ClearCaseSupport.CC_VIEW_PATH), properties.get(ClearCaseSupport.RELATIVE_PATH));
              checkDirectoryProperty(ClearCaseSupport.RELATIVE_PATH, viewPath.getWholePath(), result);
            }
          } catch (VcsException e) {
            result.add(new InvalidProperty(ClearCaseSupport.RELATIVE_PATH, e.getLocalizedMessage()));
          }

          checkGlobalLabelsVOBProperty(properties, result);
        }

        return result;
      }

      private void checkClearCaseView(String propertyName, String ccViewPath, List<InvalidProperty> result) throws VcsException, IOException {
        if (!ClearCaseConnection.isClearCaseView(ccViewPath)) {
          result.add(new InvalidProperty(propertyName, "\"" + ccViewPath + "\" is not a path to ClearCase view"));
        }
      }

      private void checkGlobalLabelsVOBProperty(final Map<String, String> properties, final List<InvalidProperty> result) {
        final boolean useGlobalLabel = "true".equals(properties.get(USE_GLOBAL_LABEL));
        if (!useGlobalLabel) return;
        final String globalLabelsVOB = properties.get(GLOBAL_LABELS_VOB);
        if (globalLabelsVOB == null || "".equals(globalLabelsVOB.trim())) {
          result.add(new InvalidProperty(GLOBAL_LABELS_VOB, "Global labels VOB must be specified"));
        }
      }
    };
  }

  @NotNull
  public String getVcsSettingsJspFilePath() {
    return "clearcaseSettings.jsp";
  }

  @NotNull
  public String getCurrentVersion(@NotNull final VcsRoot root) throws VcsException {
    return CCParseUtil.formatDate(new Date());
  }

  public boolean isCurrentVersionExpensive() {
    return false;
  }

  @NotNull
  public String getVersionDisplayName(@NotNull final String version, @NotNull final VcsRoot root) throws VcsException {
    return version;
  }

  @NotNull
  public Comparator<String> getVersionComparator() {
    return new VcsSupportUtil.DateVersionComparator(CCParseUtil.getDateFormat());
  }

  public boolean isAgentSideCheckoutAvailable() {
    return true;
  }

  @NotNull
  public String describeVcsRoot(VcsRoot vcsRoot) {
    try {
      return "clearcase: " + getViewPath(vcsRoot).getWholePath();
    } catch (VcsException e) {
      return "clearcase";
    }
  }

  public String testConnection(@NotNull VcsRoot vcsRoot) throws VcsException {
    final ClearCaseConnection caseConnection = createConnection(vcsRoot, IncludeRule.createDefaultInstance());
    try {
      try {
        return caseConnection.testConnection();
      } finally {
        caseConnection.dispose();
      }
    } catch (IOException e) {
      throw new VcsException(e);
    }
  }

  @Nullable
  public Map<String, String> getDefaultVcsProperties() {
    return new HashMap<String, String>();
  }

  @NotNull
  public Collection<String> mapFullPath(@NotNull final VcsRootEntry rootEntry, @NotNull final String fullPath) {
    final ViewPath viewPath;

    LOG.info(String.format("ClearCaseSupport.mapFullPath rootEntry=%s , fullPath=%s", rootEntry, fullPath));
    
    try {
      viewPath = getViewPath(rootEntry.getVcsRoot());
    } catch (VcsException e) {
      Loggers.VCS.debug("CC.MapFullPath: View path not defined: " + e.getLocalizedMessage());
      return Collections.emptySet();
    }

    final String serverViewRelativePath = cutOffVobsDir(viewPath.getRelativePathWithinTheView().replace("\\", "/"));

    final String normFullPath = cutOffVobsDir(fullPath.replace("\\", "/"));

    if (isAncestor(serverViewRelativePath, normFullPath)) {
      String result = normFullPath.substring(serverViewRelativePath.length());
      if (result.startsWith("/") || result.startsWith("\\")) {
        result = result.substring(1);
      }

      Loggers.VCS.debug("CC.MapFullPath: File " + normFullPath + " is under " + serverViewRelativePath + " result is " + result);
      return Collections.singleton(result);
    }
    else {
      Loggers.VCS.debug("CC.MapFullPath: File " + normFullPath + " is not under " + serverViewRelativePath);
      return Collections.emptySet();
    }
  }

  private String cutOffVobsDir(String serverViewRelativePath) {
    if (StringUtil.startsWithIgnoreCase(serverViewRelativePath, VOBS)) {
      serverViewRelativePath = serverViewRelativePath.substring(VOBS.length());
    }
    return serverViewRelativePath;
  }

  private boolean isAncestor(final String sRelPath, final String relPath) {
    return sRelPath.equalsIgnoreCase(relPath) || StringUtil.startsWithIgnoreCase(relPath, sRelPath + "/");
  }

  @NotNull
  public VcsSupportCore getCore() {
    return this;
  }

  public VcsPersonalSupport getPersonalSupport() {
    return this;
  }

  public LabelingSupport getLabelingSupport() {
    return this;
  }

  @NotNull
  public VcsFileContentProvider getContentProvider() {
    return this;
  }

  @NotNull
  public CollectChangesPolicy getCollectChangesPolicy() {
    return this;
  }

  @NotNull
  public BuildPatchPolicy getBuildPatchPolicy() {
    return this;
  }

  public List<ModificationData> collectChanges(final VcsRoot root,
                                                    final String fromVersion,
                                                    final String currentVersion,
                                                    final IncludeRule includeRule) throws VcsException, IOException {
    ClearCaseConnection connection = createConnection(root, includeRule);
    return collectChangesWithConnection(root, fromVersion, currentVersion, connection);
  }

  private List<ModificationData> collectChangesWithConnection(VcsRoot root, String fromVersion, String currentVersion,
                                                              ClearCaseConnection connection) throws VcsException {
    LOG.info(String.format("Collecting changes between %s and %s...", fromVersion, currentVersion));
    if (fromVersion == null) {
      return new ArrayList<ModificationData>();
    }
    try {
      try {
        LOG.info(String.format("Searching changes to ignore (from %s to today)...", fromVersion));
        connection.collectChangesToIgnore(currentVersion);
      } catch (Exception e) {
        throw new VcsException(e);
      }

      final List<ModificationData> list = new ArrayList<ModificationData>();
      final MultiMap<CCModificationKey, VcsChange> key2changes = new MultiMap<CCModificationKey, VcsChange>();

      final ChangedFilesProcessor fileProcessor = createCollectingChangesFileProcessor(key2changes, connection);

      try {
        CCParseUtil.processChangedFiles(connection, fromVersion, currentVersion, fileProcessor);
        LOG.info("Found " + key2changes.size() + " changes between " + fromVersion + " and " + currentVersion);

        for (CCModificationKey key : key2changes.keySet()) {
          final List<VcsChange> changes = key2changes.get(key);
          final Date date = new SimpleDateFormat(CCParseUtil.OUTPUT_DATE_FORMAT).parse(key.getDate());
          final String version = CCParseUtil.formatDate(new Date(date.getTime() + 1000));
          list.add(new ModificationData(date, changes, key.getCommentHolder().toString(), key.getUser(), root, version, version));
        }

      } catch (Exception e) {
        throw new VcsException(e);
      }

      Collections.sort(list, new Comparator<ModificationData>() {
        public int compare(final ModificationData o1, final ModificationData o2) {
          return o1.getVcsDate().compareTo(o2.getVcsDate());
        }
      });

      return list;
    } finally {
      LOG.info("Finished collecting changes.");

      try {
        connection.dispose();
      } catch (IOException e) {
        //ignore
      }
    }
  }

  public String label(@NotNull final String label, @NotNull final String version, @NotNull final VcsRoot root, @NotNull final CheckoutRules checkoutRules) throws VcsException {
    createLabel(label, root);
    throw new UnsupportedOperationException("Not yet implemented in TCCC");
  }

  private void createLabel(final String label, final VcsRoot root) throws VcsException {
    final boolean useGlobalLabel = "true".equals(root.getProperty(USE_GLOBAL_LABEL));

    String[] command;
    if (useGlobalLabel) {
      final String globalLabelsVob = root.getProperty(GLOBAL_LABELS_VOB);
      command = new String[]{"mklbtype", "-global", "-replace", "-c", "Label created by TeamCity", label + "@" + globalLabelsVob};
    } else {
      command = new String[]{"mklbtype", "-replace", "-c", "Label created by TeamCity", label};
    }

      try {
        InputStream input = ClearCaseConnection.executeSimpleProcess(getViewPath(root).getWholePath(), command);
      try {
        input.close();
      } catch (IOException e) {
        //ignore
      }
    } catch (VcsException e) {
      if (!e.getLocalizedMessage().contains("already exists")) {
        throw e;
      }
    }
  }

  public boolean sourcesUpdatePossibleIfChangesNotFound(@NotNull final VcsRoot root) {
    return false;
  }

  @NotNull
  public IncludeRuleChangeCollector getChangeCollector(@NotNull final VcsRoot root,
                                                       @NotNull final String fromVersion,
                                                       @Nullable final String currentVersion) throws VcsException {
    return new IncludeRuleChangeCollector() {
      @NotNull
      public List<ModificationData> collectChanges(@NotNull final IncludeRule includeRule) throws VcsException {
        try {
          return ClearCaseSupport.this.collectChanges(root, fromVersion, currentVersion, includeRule);
        } catch (IOException e) {
          throw new VcsException(e);
        }
      }

      public void dispose() {
        //nothing to do
      }
    };
  }

  @NotNull
  public IncludeRulePatchBuilder getPatchBuilder(@NotNull final VcsRoot root,
                                                 @Nullable final String fromVersion,
                                                 @NotNull final String toVersion) {
    return new IncludeRulePatchBuilder() {
      public void buildPatch(@NotNull final PatchBuilder builder, @NotNull final IncludeRule includeRule) throws IOException, VcsException {
        ClearCaseSupport.this.buildPatch(root, fromVersion, toVersion, builder, includeRule);
      }

      public void dispose() {
        //nothing to do
      }
    };
  }

  @Override
  public TestConnectionSupport getTestConnectionSupport() {
    return this;
  }

  private class MyChangedFilesProcessor implements ChangedFilesProcessor {
    private final MultiMap<CCModificationKey,
          VcsChange> key2changes;
    private final ClearCaseConnection connection;
    private final Map<String, String> activityIdToHeadline = new HashMap<String, String>(); 

    public MyChangedFilesProcessor(MultiMap<CCModificationKey,
          VcsChange> key2changes, ClearCaseConnection connection) {
      this.key2changes = key2changes;
      this.connection = connection;
    }

    public void processChangedDirectory(final HistoryElement element) throws IOException, VcsException {
      final String versionBeforeChange = element.getPreviousVersion();
      final String versionAfterChange = element.getObjectVersion();
      addChange(element, element.getObjectName(), VcsChangeInfo.Type.CHANGED, versionBeforeChange, versionAfterChange,
          key2changes, connection);
    }

    public void processDestroyedFileVersion(final HistoryElement element) throws VcsException {
    }

    public void processChangedFile(final HistoryElement element) throws VcsException, IOException {
      final String versionBeforeChange = element.getPreviousVersion();
      final String versionAfterChange = element.getObjectVersion();
      addChange(element, element.getObjectName(), VcsChangeInfo.Type.CHANGED, versionBeforeChange, versionAfterChange,
          key2changes, connection);
    }

    private void addChange(final HistoryElement element,
                           final String childFullPath,
                           final VcsChangeInfo.Type type,
                           final String beforeVersion,
                           final String afterVersion,
                           final MultiMap<CCModificationKey, VcsChange> key2changes, ClearCaseConnection connection) throws VcsException, IOException {
      final CCModificationKey modificationKey = new CCModificationKey(element.getDate(), element.getUser());
      VcsChange vcsChange = createChange(type, beforeVersion, afterVersion, childFullPath);
      key2changes.putValue(modificationKey, vcsChange);
      CCModificationKey realKey = findKey(modificationKey, key2changes);
      String activityId = element.getActivity();
      String activityHeadline;
      if (activityIdToHeadline.containsKey(activityId)) {
        activityHeadline = activityIdToHeadline.get(activityId);
      } else {
        activityHeadline = connection.getActivityHeadline(activityId);
        activityIdToHeadline.put(activityId, activityHeadline);
      }
      if (realKey != null) {
        realKey.getCommentHolder().update(activityHeadline, element.getComment());
      }
      Loggers.VCS.info("Added change : " + vcsChange);
    }

  }
}
