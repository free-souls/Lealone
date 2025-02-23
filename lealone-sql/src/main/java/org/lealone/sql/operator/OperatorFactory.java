/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.sql.operator;

import org.lealone.db.PluggableEngine;
import org.lealone.db.result.LocalResult;
import org.lealone.sql.query.Select;

public interface OperatorFactory extends PluggableEngine {

    Operator createOperator(Select select);

    default Operator createOperator(Select select, LocalResult localResult) {
        return createOperator(select);
    }

}
