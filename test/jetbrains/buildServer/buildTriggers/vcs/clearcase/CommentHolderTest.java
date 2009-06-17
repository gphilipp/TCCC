package jetbrains.buildServer.buildTriggers.vcs.clearcase;

import junit.framework.TestCase;

/**
 * @author Gilles Philippart
 */
public class CommentHolderTest extends TestCase {

  public void testFormat() {
    CommentHolder commenHolder = new CommentHolder();
    commenHolder.addActivity("a1");
    commenHolder.addComment("c1");
    StringBuffer sb = new StringBuffer();
    commenHolder.format(sb, commenHolder.getActivities(), "Activity", "Activities");
    commenHolder.format(sb, commenHolder.getComments(), "Comment", "Comments");
    System.out.println(sb);

    commenHolder.addActivity("a2");
    commenHolder.addComment("c2");
    sb = new StringBuffer();
    commenHolder.format(sb, commenHolder.getActivities(), "Activity", "Activities");
    commenHolder.format(sb, commenHolder.getComments(), "Comment", "Comments");
    System.out.println(sb);
  }
}
