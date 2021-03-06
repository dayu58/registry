package cn.lzj.nacos.naming.consistency;

import cn.lzj.nacos.naming.misc.GlobalExecutor;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class TaskDispatcher {

    public volatile TaskScheduler taskScheduler=new TaskScheduler();

    @PostConstruct
    public void init() {
        GlobalExecutor.submitTaskDispatch(taskScheduler);
    }

    public void addTask(String key) {
        taskScheduler.addTask(key);
    }

    class TaskScheduler implements Runnable{

        private BlockingQueue<String> queue = new LinkedBlockingQueue<>(128 * 1024);

        public void addTask(String key) {
            queue.offer(key);
        }

        @Override
        public void run() {

        }
    }
}
