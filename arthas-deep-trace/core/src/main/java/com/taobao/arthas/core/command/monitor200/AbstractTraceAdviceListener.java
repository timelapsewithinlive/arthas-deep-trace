package com.taobao.arthas.core.command.monitor200;

import com.taobao.arthas.core.advisor.Advice;
import com.taobao.arthas.core.advisor.ArthasMethod;
import com.taobao.arthas.core.advisor.ReflectAdviceListenerAdapter;
import com.taobao.arthas.core.shell.command.CommandProcess;
import com.taobao.arthas.core.util.LogUtil;
import com.taobao.arthas.core.util.ThreadLocalWatch;
import com.taobao.arthas.core.view.TreeView;

/**
 * @author ralf0131 2017-01-06 16:02.
 */
public class AbstractTraceAdviceListener extends ReflectAdviceListenerAdapter {

    protected final ThreadLocalWatch threadLocalWatch = new ThreadLocalWatch();
    protected TraceCommand command;
    protected CommandProcess process;

    protected final ThreadLocal<TraceEntity> threadBoundEntity = new ThreadLocal<TraceEntity>() {

        @Override
        protected TraceEntity initialValue() {
            return new TraceEntity();
        }
    };

    /**
     * Constructor
     */
    public AbstractTraceAdviceListener(TraceCommand command, CommandProcess process) {
        this.command = command;
        this.process = process;
    }

    @Override
    public void destroy() {
        threadBoundEntity.remove();
    }

    @Override
    public void before(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args)
            throws Throwable {
        threadBoundEntity.get().view.begin(clazz.getName() + ":" + method.getName() + "()", TreeView.INVOKE_ON_BEGIN);
        threadBoundEntity.get().deep++;
        // 开始计算本次方法调用耗时
        threadLocalWatch.start();
    }

    @Override
    public void afterReturning(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args,
                               Object returnObject) throws Throwable {
        threadBoundEntity.get().view.end();
        final Advice advice = Advice.newForAfterRetuning(loader, clazz, method, target, args, returnObject);
        finishing(advice);
    }

    @Override
    public void afterThrowing(ClassLoader loader, Class<?> clazz, ArthasMethod method, Object target, Object[] args,
                              Throwable throwable) throws Throwable {
        threadBoundEntity.get().view.begin("throw:" + throwable.getClass().getName() + "()", TreeView.INVOKE_AFTER_THROWING).end().end();
        final Advice advice = Advice.newForAfterThrowing(loader, clazz, method, target, args, throwable);
        finishing(advice);
    }

    public TraceCommand getCommand() {
        return command;
    }

    private void finishing(Advice advice) {
        // 本次调用的耗时
        double cost = threadLocalWatch.costInMillis();
        if (--threadBoundEntity.get().deep == 0) {
            try {
                if (isConditionMet(command.getConditionExpress(), advice, cost) && !shouldIgnoreTrace()) {
                    // 满足输出条件
                    if (isLimitExceeded(command.getNumberOfLimit(), process.times().get())) {
                        // TODO: concurrency issue to abort process
                        abortProcess(process, command.getNumberOfLimit());
                    } else {
                        // TODO: concurrency issues for process.write
                        TreeView view = threadBoundEntity.get().view;
                        command.onTraceResult(process, view);
//                        view.pretty();
//                        process.write(view.draw() + "\n");
                        process.times().incrementAndGet();

                    }
                }
            } catch (Throwable e) {
                LogUtil.getArthasLogger().warn("trace failed.", e);
                process.write("trace failed, condition is: " + command.getConditionExpress() + ", " + e.getMessage()
                              + ", visit " + LogUtil.LOGGER_FILE + " for more details.\n");
                process.end();
            } finally {
                threadBoundEntity.remove();
            }
        }
    }

    /**
     * check if the first level trace method
     * @return
     */
    protected boolean shouldIgnoreTrace() {
        String rootData = threadBoundEntity.get().view.getTopTraceData();
        String[] strs = rootData.split(":");
        String className = strs[0];
        String methodName = strs[1].replaceAll("\\(\\)", "");
        if (strs.length>=2){
//            if(command.getClassNameMatcher().matching(className) && command.getMethodNameMatcher().matching(methodName)) {
//                return false;
//            }
            if(command.isFirstLevelEnhanceMethod(className, methodName)){
                return false;
            }
        }

//        TreeView view = threadBoundEntity.get().view;
//        if(view.isLittleTree()) {
//           return true;
//        }
        return true;
    }
}
