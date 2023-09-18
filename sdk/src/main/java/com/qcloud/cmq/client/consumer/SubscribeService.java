package com.qcloud.cmq.client.consumer;

import com.qcloud.cmq.client.client.ThreadGroupFactory;
import com.qcloud.cmq.client.common.LogHelper;
import com.qcloud.cmq.client.common.RemoteHelper;
import com.qcloud.cmq.client.common.ResponseCode;
import com.qcloud.cmq.client.common.RequestIdHelper;
import com.qcloud.cmq.client.exception.MQClientException;
import com.qcloud.cmq.client.netty.CommunicationMode;
import com.qcloud.cmq.client.netty.RemoteException;
import com.qcloud.cmq.client.protocol.Cmq;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SubscribeService {
    private final static Logger logger = LogHelper.getLog();

    private final String queue;
    private final MessageListener listener;
    private final Cmq.CMQProto.Builder pullRequestBuilder;
    private final ConsumerImpl consumer;

    private final BlockingQueue<Runnable> consumeRequestQueue;
    private final ThreadPoolExecutor consumeExecutor;
    private final ScheduledExecutorService scheduledExecutorService;
    private AtomicInteger flightPullRequest = new AtomicInteger();

    SubscribeService(String queue, MessageListener listener, Cmq.CMQProto.Builder builder, ConsumerImpl consumer,
                     int pullMessageThreadCorePoolSize, int consumeMessageThreadCorePoolSize, int consumeMessageThreadMaxPoolSize) {
        this.queue = queue;
        this.listener = listener;
        this.pullRequestBuilder = builder;
        this.consumer = consumer;

        this.scheduledExecutorService = Executors.newScheduledThreadPool(pullMessageThreadCorePoolSize, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "PullMessageScheduledThread");
            }
        });

        this.consumeRequestQueue = new LinkedBlockingQueue<Runnable>();
        this.consumeExecutor = new ThreadPoolExecutor(consumeMessageThreadCorePoolSize, consumeMessageThreadMaxPoolSize,
                1000 * 60, TimeUnit.MILLISECONDS,
                this.consumeRequestQueue, new ThreadGroupFactory("ConsumeMessageThread_"));
    }

    private void startScheduleTask() {
        this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                logger.debug("schedule flightPullRequest:{}, size:{}, active: {}",
                        flightPullRequest.get(), consumeRequestQueue.size(), consumeExecutor.getActiveCount());
                pullRequestImmediately();
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
    }

    private void pullRequestImmediately() {
        if (flightPullRequest.get() < 16 && consumeRequestQueue.size() < 16) {
            flightPullRequest.incrementAndGet();
            try {
                SubscribeService.this.pullImmediately();
            } catch (Throwable e) {
                logger.error("pull message error", e);
                SubscribeService.this.consumer.setNeedUpdateRoute();
            } finally {
                flightPullRequest.decrementAndGet();
            }
        }
    }

    private void submitPullRequest() {
        this.scheduledExecutorService.schedule(new Runnable() {
            @Override
            public void run() {
                logger.debug("submit flightPullRequest:{}, size:{}", flightPullRequest.get(), consumeRequestQueue.size());
                pullRequestImmediately();
            }
        }, 0, TimeUnit.MILLISECONDS);
    }

    private void pullImmediately() throws RemoteException, InterruptedException, MQClientException {
        Cmq.CMQProto request = pullRequestBuilder.setSeqno(RequestIdHelper.getNextSeqNo()).build();
        int timeoutMS = this.consumer.getConsumer().getRequestTimeoutMS() + this.consumer.getConsumer().getPollingWaitSeconds() * 1000;


        List<String> accessList = consumer.getQueueRoute(this.queue);
        this.consumer.getMQClientInstance().getCMQClient().batchReceiveMessage(accessList, request, timeoutMS,
                CommunicationMode.ASYNC, new BatchReceiveCallback() {
                    @Override
                    public void onSuccess(BatchReceiveResult receiveResult) {
                        if (receiveResult.getReturnCode() == ResponseCode.SUCCESS) {
                            consumeExecutor.submit(new ConsumeRequest(receiveResult.getMessageList()));
                        } else if (receiveResult.getReturnCode() != ResponseCode.NO_NEW_MESSAGES) {
                            logger.info("pull message error:" + receiveResult.getErrorMessage() + ", errorCode:"
                                    + receiveResult.getReturnCode());
                        }
                    }

                    @Override
                    public void onException(Throwable e) {
                        logger.info("pull message error :" + e.getMessage());
                    }
                });
    }

    public void start() {
        this.startScheduleTask();
    }

    public void shutdown() {
        this.scheduledExecutorService.shutdown();
        this.consumeExecutor.shutdown();
        logger.info("shutdown pull service for queue {} success.", queue);
    }

    class ConsumeRequest implements Runnable {

        private final List<Message> msgList;

        ConsumeRequest(List<Message> msgList) {
            this.msgList = msgList;
        }

        @Override
        public void run() {
            try {
                List<Long> ackMsg = listener.consumeMessage(queue, Collections.unmodifiableList(msgList));
                if (ackMsg != null && !ackMsg.isEmpty()) {
                    consumer.getConsumer().batchDeleteMsg(queue, ackMsg, new BatchDeleteCallback() {
                        @Override
                        public void onSuccess(BatchDeleteResult deleteResult) {
                            logger.debug("delete msg success, result{}", deleteResult);
                        }

                        @Override
                        public void onException(Throwable e) {
                            logger.debug("delete msg failed", e);
                        }
                    });
                }
                SubscribeService.this.submitPullRequest();
            } catch (Throwable e) {
                logger.warn("consumeMessage exception: {} queue: {}", RemoteHelper.exceptionSimpleDesc(e), queue);
            }
        }

    }
}
