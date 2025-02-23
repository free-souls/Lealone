/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.test.main;

import org.lealone.main.Shell;

public class ShellTest {

    public static void main(String[] args) {
        System.setProperty("lealone.config", "lealone-test.yaml");
        String url = "jdbc:lealone:tcp://localhost:9210/lealone";
        // url = "jdbc:lealone:embed:lealone";
        String[] args2 = { "-url", url, "-user", "root" };
        Shell.main(args2);
    }
}
