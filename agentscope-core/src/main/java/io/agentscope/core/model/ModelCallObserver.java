package io.agentscope.core.model;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ModelCallEndEvent;

/** 可选的模型调用观察者，独立于展示事件流进行资源管理与用量结算。 */
public interface ModelCallObserver {
    String CONTEXT_KEY = "agentscope.model.call_observer";

    /** 模型请求前调用，callId 为该次订阅的 replyId；抛出异常会阻止请求。 */
    void onStart(RuntimeContext context, String callId);

    /** 已开始的调用在完成、异常或取消时回调一次；usage 可为空，不做估算。 */
    void onEnd(RuntimeContext context, ModelCallEndEvent event);
}
