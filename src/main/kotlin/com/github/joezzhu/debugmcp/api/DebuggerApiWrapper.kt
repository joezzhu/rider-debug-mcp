package com.github.joezzhu.debugmcp.api

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.*
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DebuggerApiWrapper(private val project: Project) {

    private val debuggerManager: XDebuggerManager
        get() = XDebuggerManager.getInstance(project)

    fun getActiveSessions(): List<XDebugSession> {
        return debuggerManager.debugSessions.toList()
    }

    fun getSessionByName(name: String): XDebugSession? {
        return getActiveSessions().find { it.sessionName == name }
    }

    fun getBreakpoints(): List<XBreakpoint<*>> {
        return debuggerManager.breakpointManager.allBreakpoints.toList()
    }

    suspend fun listThreads(sessionName: String): List<XExecutionStack> {
        val session = getSessionByName(sessionName) ?: return emptyList()
        val suspendContext = session.suspendContext ?: return emptyList()
        return suspendContext.executionStacks.toList()
    }

    suspend fun getStackTrace(sessionName: String, threadName: String): List<XStackFrame> {
        val threads = listThreads(sessionName)
        val thread = threads.find { it.displayName == threadName } ?: return emptyList()
        
        return suspendCancellableCoroutine { continuation ->
            thread.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
                private val frames = mutableListOf<XStackFrame>()
                override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                    frames.addAll(stackFrames)
                    if (last) {
                        continuation.resume(frames)
                    }
                }

                override fun errorOccurred(errorMessage: String) {
                    continuation.resumeWithException(Exception(errorMessage))
                }
            })
        }
    }

    suspend fun getVariables(sessionName: String, threadName: String, frameIndex: Int): List<XValue> {
        val frames = getStackTrace(sessionName, threadName)
        if (frameIndex !in frames.indices) return emptyList()
        val frame = frames[frameIndex]

        return suspendCancellableCoroutine { continuation ->
            val result = mutableListOf<XValue>()
            frame.computeChildren(object : XCompositeNode {
                override fun addChildren(children: XValueChildrenList, last: Boolean) {
                    for (i in 0 until children.size()) {
                        result.add(children.getValue(i))
                    }
                    if (last) {
                        continuation.resume(result)
                    }
                }

                override fun tooManyChildren(remaining: Int) {}
                override fun tooManyChildren(remaining: Int, addNextChildrenRunnable: Runnable) {}
                override fun setAlreadySorted(alreadySorted: Boolean) {}
                override fun setErrorMessage(errorMessage: String) {
                    continuation.resumeWithException(Exception(errorMessage))
                }
                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                    continuation.resumeWithException(Exception(errorMessage))
                }
                override fun setMessage(message: String, icon: javax.swing.Icon?, attributes: com.intellij.ui.SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
            })
        }
    }

    suspend fun evaluateExpression(sessionName: String, threadName: String, frameIndex: Int, expression: String): String {
        val frames = getStackTrace(sessionName, threadName)
        if (frameIndex !in frames.indices) return "Error: Frame index out of bounds"
        val frame = frames[frameIndex]
        val evaluator = frame.evaluator ?: return "Error: No evaluator available for this frame"

        return suspendCancellableCoroutine { continuation ->
            evaluator.evaluate(expression, object : XDebuggerEvaluator.XEvaluationCallback {
                override fun evaluated(result: XValue) {
                    continuation.resume("Evaluation successful. Value node available.")
                }

                override fun errorOccurred(errorMessage: String) {
                    continuation.resumeWithException(Exception(errorMessage))
                }
            }, null)
        }
    }
}
