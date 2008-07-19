/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package org.terracotta.modules.tool.commands;


/**
 * Test case for {@link ListCommand}.
 */
public class ListCommandTest extends AbstractCommandTestCase {

  @Override
  protected void setUp() throws Exception {
    super.setUp();
  }

  /**
   * Test method for {@link org.terracotta.modules.tool.commands.ListCommand#execute()}.
   */
  public final void testExecute() {
//    command.execute(null);
//    assertOutMatches("TIM packages for Terracotta " + ProductInfo.getInstance().version());
//    assertEquals(0, commandErr.toString().length());
  }

  @Override
  protected AbstractCommand createCommand() {
    return new ListCommand(null);
  }

}
