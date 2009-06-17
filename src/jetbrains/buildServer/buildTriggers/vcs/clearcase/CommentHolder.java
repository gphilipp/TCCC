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

import java.util.LinkedHashSet;
import java.util.Set;

public class CommentHolder {
  private final Set<String> myActivities = new LinkedHashSet<String>();
  private final Set<String> myComments = new LinkedHashSet<String>();

  public void addActivity(String str) {
    if (str != null && str.length() > 0) {
      myActivities.add(str);
    }
  }


  public void addComment(String str) {
    if (str != null && str.length() > 0) {
      if (!myComments.contains(str)) {
        myComments.add(str);
      }
    }
  }

  public Set<String> getActivities() {
    return new LinkedHashSet<String>(myActivities);
  }

  public Set<String> getComments() {
    return new LinkedHashSet<String>(myComments);
  }

  public String toString() {
    final StringBuffer sb = new StringBuffer();
    format(sb, myActivities, "Activity", "Activities");
    format(sb, myComments, "Comment", "Comments");
    return sb.toString();
  }

  void format(StringBuffer result, Set<String> list, String singular, String plural) {
    if (!list.isEmpty()) {
      result.append(list.size() > 1 ? plural : singular).append(" : ");
      if (list.size() > 1) {
          result.append('\n');
        }
      for (String s : list) {
        result.append(s).append('\n');
      }
    }
  }

  public void update(final String activity, final String comment) {
    addActivity(activity);
    addComment(comment);
  }
}
