package zio.flow.internal

import zio.flow.{FlowId, TransactionId}

/**
 * Describes a remote variable's scope
 *
 * ## Scoping rules
 *
 * ### Workflows
 * A top level workflow defines the top level scope by it's unique flow identifier.
 * This guarantees that:
 *  - separate workflows cannot see each other's variables
 *  - restarted workflows see the same set of variables as the previous run as they share the flow id
 *
 *  Operation semantics on the top level of workflows (not in a forked fiber and not in an active transaction):
 *
 *  - New(name): creates a new remote variable in the KV store's variable namespace called `"$flowid__$name"``
 *  - Get(name): reads`"$flowid__$name"`
 *  - Set(name): writes`"$flowid__$name"`
 *
 * ### Fibers
 * Forked workflows are like rergular workflows but they are not individually submitted, instead created by the
 * executor by the Fork operator.
 *
 * Each workflow maintains a fork counter and generates new workflow ids based on that. So a forked workflow's
 * flow identifier will be `"$parentId_fork$parentForkCounter"`.
 *
 * Desired semantics:
 *  - Forked workflows should have read/write access to variables accessible to the parent workflow
 *  - Creating new variables in a forked workflow should not be accessible to the parent and sibling workflows
 *  - Parallel forked workflows should be able to create independent variables with the same name
 *
 * Operation semantics in forked workflows:
 *
 * - New(name): creates a new remote variable in the KV store's variable namespace prefixed by the
 *              active workflow identifier `"$flowid__$name"` (which is `"$parentId_fork$parentForkCounter__$name"`).
 * - Get(name): first finds the variable's scope by first looking in the current fiber's scope
 *              (using `"$flowid__$name"`) - if it does not exist, it recursively tries to access the variable in the
 *              parent scope (`"$parentid__$name"`).
 * - Set(name): same lookup as for Get - Get and Set must always select the same variable in an executor step
 *
 * ### Transactions
 *
 * In transactions we have to delay the effect of Set (but within the transaction still see that value in Get) until
 * the transaction is committed. This means that we need to store values for the same remote variable name
 * per transaction beside its original value - which means transactions define their own scope.
 *
 * Desired semantics:
 *  - Creating a new variable in a transaction: should not behave differently than in a regular scope
 *    - transactional variable updates are only interesting if there are multiple fibers running transactions modifying
 *      the same variable. This means that even if there are "colliding" new variables in parallel transactions, their
 *      parent scope will be different (because fibers are also defining scopes) so they would never collide.
 *  - Within the transaction, Get and Set should work as usual, but the effect of Set should not be visible for other
 *    fibers, even if the changed variable is in a shared scope.
 *  - When the transaction is committed, the changes are either applied to these shared variables, or the transaction gets
 *    reverted.
 *
 *
 * Flow state contains a transaction counter that can be used as a unique identifier for transaction scopes, similar to
 * how fiber scopes are generated: `"parentId_tx$transactionCounter"`.
 *
 * Operation semantics in transaction scopes:
 *
 *  - New(name): creates a new remote variable in the parent scope
 *  - Get(name): acts the same way as in forked workflows, but also records the accessed variable's version if necessary
 *  - Set(name): always sets the value in the transaction scope (`$parentid__$name`)
 */
final case class RemoteVariableScope(flowId: FlowId, transactionId: Option[TransactionId])
