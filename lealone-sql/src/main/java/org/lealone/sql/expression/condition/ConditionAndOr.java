/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.expression.condition;

import org.lealone.common.exceptions.DbException;
import org.lealone.db.SysProperties;
import org.lealone.db.session.ServerSession;
import org.lealone.db.value.Value;
import org.lealone.db.value.ValueBoolean;
import org.lealone.db.value.ValueNull;
import org.lealone.sql.expression.Expression;
import org.lealone.sql.expression.ValueExpression;
import org.lealone.sql.expression.visitor.ExpressionVisitor;
import org.lealone.sql.optimizer.TableFilter;

/**
 * An 'and' or 'or' condition as in WHERE ID=1 AND NAME=?
 */
public class ConditionAndOr extends Condition {

    /**
     * The AND condition type as in ID=1 AND NAME='Hello'.
     */
    public static final int AND = 0;

    /**
     * The OR condition type as in ID=1 OR NAME='Hello'.
     */
    public static final int OR = 1;

    private final int andOrType;
    private Expression left, right;

    public ConditionAndOr(int andOrType, Expression left, Expression right) {
        this.andOrType = andOrType;
        this.left = left;
        this.right = right;
        if (SysProperties.CHECK && (left == null || right == null)) {
            DbException.throwInternalError();
        }
    }

    public Expression getLeft() {
        return left;
    }

    public Expression getRight() {
        return right;
    }

    public int getAndOrType() {
        return andOrType;
    }

    @Override
    public String getSQL(boolean isDistributed) {
        String sql;
        switch (andOrType) {
        case AND:
            sql = left.getSQL(isDistributed) + "\n    AND " + right.getSQL(isDistributed);
            break;
        case OR:
            sql = left.getSQL(isDistributed) + "\n    OR " + right.getSQL(isDistributed);
            break;
        default:
            throw DbException.getInternalError("andOrType=" + andOrType);
        }
        return "(" + sql + ")";
    }

    @Override
    public void createIndexConditions(ServerSession session, TableFilter filter) {
        if (andOrType == AND) {
            left.createIndexConditions(session, filter);
            right.createIndexConditions(session, filter);
        }
    }

    @Override
    public Expression getNotIfPossible(ServerSession session) {
        // (NOT (A OR B)): (NOT(A) AND NOT(B))
        // (NOT (A AND B)): (NOT(A) OR NOT(B))
        Expression l = left.getNotIfPossible(session);
        if (l == null) {
            l = new ConditionNot(left);
        }
        Expression r = right.getNotIfPossible(session);
        if (r == null) {
            r = new ConditionNot(right);
        }
        int reversed = andOrType == AND ? OR : AND;
        return new ConditionAndOr(reversed, l, r);
    }

    @Override
    public Value getValue(ServerSession session) {
        Value l = left.getValue(session);
        Value r;
        switch (andOrType) {
        case AND: {
            if (!l.getBoolean()) {
                return l;
            }
            r = right.getValue(session);
            if (!r.getBoolean()) {
                return r;
            }
            if (l == ValueNull.INSTANCE) {
                return l;
            }
            if (r == ValueNull.INSTANCE) {
                return r;
            }
            return ValueBoolean.get(true);
        }
        case OR: {
            if (l.getBoolean()) {
                return l;
            }
            r = right.getValue(session);
            if (r.getBoolean()) {
                return r;
            }
            if (l == ValueNull.INSTANCE) {
                return l;
            }
            if (r == ValueNull.INSTANCE) {
                return r;
            }
            return ValueBoolean.get(false);
        }
        default:
            throw DbException.getInternalError("type=" + andOrType);
        }
    }

    @Override
    public Expression optimize(ServerSession session) {
        // NULL handling: see wikipedia,
        // http://www-cs-students.stanford.edu/~wlam/compsci/sqlnulls
        left = left.optimize(session);
        right = right.optimize(session);
        int lc = left.getCost(), rc = right.getCost();
        if (rc < lc) {
            Expression t = left;
            left = right;
            right = t;
        }
        // this optimization does not work in the following case,
        // but NOT is optimized before:
        // CREATE TABLE TEST(A INT, B INT);
        // INSERT INTO TEST VALUES(1, NULL);
        // SELECT * FROM TEST WHERE NOT (B=A AND B=0); // no rows
        // SELECT * FROM TEST WHERE NOT (B=A AND B=0 AND A=0); // 1, NULL
        if (session.getDatabase().getSettings().optimizeTwoEquals && andOrType == AND) {
            // try to add conditions (A=B AND B=1: add A=1)
            if (left instanceof Comparison && right instanceof Comparison) {
                Comparison compLeft = (Comparison) left;
                Comparison compRight = (Comparison) right;
                Expression added = compLeft.getAdditional(session, compRight, true);
                if (added != null) {
                    added = added.optimize(session);
                    ConditionAndOr a = new ConditionAndOr(AND, this, added);
                    return a;
                }
            }
        }
        // TODO optimization: convert ((A=1 AND B=2) OR (A=1 AND B=3)) to
        // (A=1 AND (B=2 OR B=3))
        if (andOrType == OR && session.getDatabase().getSettings().optimizeOr) {
            // try to add conditions (A=B AND B=1: add A=1)
            if (left instanceof Comparison && right instanceof Comparison) {
                Comparison compLeft = (Comparison) left;
                Comparison compRight = (Comparison) right;
                Expression added = compLeft.getAdditional(session, compRight, false);
                if (added != null) {
                    return added.optimize(session);
                }
            } else if (left instanceof ConditionIn && right instanceof Comparison) {
                Expression added = ((ConditionIn) left).getAdditional((Comparison) right);
                if (added != null) {
                    return added.optimize(session);
                }
            } else if (right instanceof ConditionIn && left instanceof Comparison) {
                Expression added = ((ConditionIn) right).getAdditional((Comparison) left);
                if (added != null) {
                    return added.optimize(session);
                }
            } else if (left instanceof ConditionInConstantSet && right instanceof Comparison) {
                Expression added = ((ConditionInConstantSet) left).getAdditional(session, (Comparison) right);
                if (added != null) {
                    return added.optimize(session);
                }
            } else if (right instanceof ConditionInConstantSet && left instanceof Comparison) {
                Expression added = ((ConditionInConstantSet) right).getAdditional(session, (Comparison) left);
                if (added != null) {
                    return added.optimize(session);
                }
            }
        }
        // TODO optimization: convert .. OR .. to UNION if the cost is lower
        Value l = left.isConstant() ? left.getValue(session) : null;
        Value r = right.isConstant() ? right.getValue(session) : null;
        if (l == null && r == null) {
            return this;
        }
        if (l != null && r != null) {
            return ValueExpression.get(getValue(session));
        }
        switch (andOrType) {
        case AND:
            if (l != null) {
                if (Boolean.FALSE.equals(l.getBoolean())) {
                    return ValueExpression.get(l);
                } else if (Boolean.TRUE.equals(l.getBoolean())) {
                    return right;
                }
            } else if (r != null) {
                if (Boolean.FALSE.equals(r.getBoolean())) {
                    return ValueExpression.get(r);
                } else if (Boolean.TRUE.equals(r.getBoolean())) {
                    return left;
                }
            }
            break;
        case OR:
            if (l != null) {
                if (Boolean.TRUE.equals(l.getBoolean())) {
                    return ValueExpression.get(l);
                } else if (Boolean.FALSE.equals(l.getBoolean())) {
                    return right;
                }
            } else if (r != null) {
                if (Boolean.TRUE.equals(r.getBoolean())) {
                    return ValueExpression.get(r);
                } else if (Boolean.FALSE.equals(r.getBoolean())) {
                    return left;
                }
            }
            break;
        default:
            DbException.throwInternalError("type=" + andOrType);
        }
        return this;
    }

    @Override
    public void addFilterConditions(TableFilter filter, boolean outerJoin) {
        if (andOrType == AND) {
            left.addFilterConditions(filter, outerJoin);
            right.addFilterConditions(filter, outerJoin);
        } else {
            super.addFilterConditions(filter, outerJoin);
        }
    }

    @Override
    public int getCost() {
        return left.getCost() + right.getCost();
    }

    /**
     * Get the left or the right sub-expression of this condition.
     *
     * @param getLeft true to get the left sub-expression, false to get the right
     *            sub-expression.
     * @return the sub-expression
     */
    public Expression getExpression(boolean getLeft) {
        return getLeft ? this.left : right;
    }

    @Override
    public <R> R accept(ExpressionVisitor<R> visitor) {
        return visitor.visitConditionAndOr(this);
    }
}
