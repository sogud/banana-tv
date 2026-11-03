package com.sogud.bananatv.source.script;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Message;
import android.os.Messenger;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.sogud.bananatv.source.script.ScriptSession.Operation.*;

/** Persistent state, real cross-process storage and owner-specific termination. */
final class PersistentWorkerProbe {
    private static final String CODE = "let count=0,prefix;export default {init(value){prefix=value;local.set('host','inits',String(Number(local.get('host','inits')||0)+1));},"
            + "home(){return {count:++count,prefix,inits:Number(local.get('host','inits'))};},"
            + "category(){return {count:++count};},detail(value){if(value==='fail')throw new Error('private-fixture-key');local.set('shared','key',value);return {};},"
            + "play(){return {value:local.get('shared','key')};},proxy(){return {count};},"
            + "search(){local.set('host','loop','entered');while(true){}}};";
    private static ScriptWorkerClient.Source source(String owner, String code) {
        return new ScriptWorkerClient.Source("source.js", Collections.singletonMap("source.js",code),owner,
                ScriptWorkerClient.Profile.NONE,new JSONArray().put("seed"));
    }
    private static JSONObject call(ScriptWorkerClient client, ScriptSession.Operation operation, Object... arguments) throws Exception {
        JSONArray array = new JSONArray(); for (Object value : arguments) array.put(value);
        return new JSONObject(client.call(operation,array,5000));
    }
    static void run(Instrumentation instrumentation, Consumer<String> passed) throws Exception {
        Context context = instrumentation.getContext(); String root = "persistent-probe-" + UUID.randomUUID();
        try {
            stateAndStorage(context,root,passed);
            isolationAndRecovery(context,root,passed);
            destroyAndStaleControl(context,root,passed);
            AtomicReference<Throwable> rejected = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> { try { ScriptWorkerClient.open(context,source(root,CODE),1000); }
                catch (Throwable error) { rejected.set(error); } });
            require(rejected.get() instanceof IllegalStateException,"persistent_client_rejects_blocking_main_thread_open",passed);
        } finally {
            try (ScriptDatabase db = new ScriptDatabase(context)) {
                for (String suffix : new String[]{"a","b","loop","protected","destroy","new","large","mutable"}) db.removeSource(root+suffix);
            }
        }
    }
    private static void stateAndStorage(Context context,String root,Consumer<String> passed) throws Exception {
        try (ScriptWorkerClient first=ScriptWorkerClient.open(context,source(root+"a",CODE),5000);
             ScriptWorkerClient shared=ScriptWorkerClient.open(context,source(root+"a",CODE),5000);
             ScriptWorkerClient other=ScriptWorkerClient.open(context,source(root+"b",CODE),5000)) {
            int pid=first.workerPid(); JSONObject firstHome=call(first,HOME), second=call(first,CATEGORY), third=call(first,HOME);
            require(firstHome.getInt("count")==1&&second.getInt("count")==2&&third.getInt("count")==3&&first.workerPid()==pid,
                    "persistent_calls_keep_interpreter_state_and_process",passed);
            call(shared,PLAY); call(first,DETAIL,"one");
            require(call(shared,PLAY).getString("value").equals("one"),"persistent_sqlite_reads_new_values_from_another_live_process",passed);
            call(shared,DETAIL,"two");
            require(call(first,PLAY).getString("value").equals("two")&&call(other,PLAY).getString("value").isEmpty(),
                    "persistent_storage_is_coherent_and_source_owned",passed);
            boolean redacted=false;
            try { call(first,DETAIL,"fail"); } catch (IOException error) { redacted=!error.toString().contains("private-fixture-key"); }
            require(redacted&&call(first,HOME).getInt("count")==4&&first.workerPid()==pid,
                    "source_exception_is_redacted_without_discarding_healthy_session",passed);
            ScriptProcess current=process(first);
            JSONObject stale=new JSONObject().put("generation","stale").put("operation","HOME").put("arguments",new JSONArray());
            JSONObject response=current.exchange(ScriptWorkerService.CALL,stale,android.os.SystemClock.elapsedRealtime()+2000);
            require(response.has("error")&&call(first,HOME).getInt("count")==5,"stale_generation_cannot_execute_in_persistent_context",passed);
        }
        try (ScriptWorkerClient large=ScriptWorkerClient.open(context,source(root+"large","export default {home(){return 'x'.repeat(100000);}}"),5000)) {
            require(large.call(HOME,new JSONArray(),5000).length()==100000,"persistent_pipe_handles_catalogue_larger_than_legacy_batch_limit",passed);
        }
        java.util.Map<String,String> modules=new java.util.HashMap<>();modules.put("source.js",CODE);
        JSONArray initialization=new JSONArray().put("original");
        ScriptWorkerClient.Source snapshot=new ScriptWorkerClient.Source("source.js",modules,root+"mutable",ScriptWorkerClient.Profile.NONE,initialization);
        modules.put("source.js","bad source");initialization.put(0,"changed");
        try (ScriptWorkerClient client=ScriptWorkerClient.open(context,snapshot,5000)) {
            require(call(client,HOME).getString("prefix").equals("original"),"source_definition_snapshots_modules_and_init_arguments",passed);
        }
    }
    private static void isolationAndRecovery(Context context,String root,Consumer<String> passed) throws Exception {
        try (ScriptWorkerClient looping=ScriptWorkerClient.open(context,source(root+"loop",CODE),5000);
             ScriptWorkerClient protectedClient=ScriptWorkerClient.open(context,source(root+"protected",CODE),5000)) {
            int old=looping.workerPid(),protectedPid=protectedClient.workerPid();call(protectedClient,HOME);
            List<ScriptWorkerClient> capacity=new ArrayList<>();
            try {
                capacity.add(ScriptWorkerClient.open(context,source(root+"a",CODE),5000));capacity.add(ScriptWorkerClient.open(context,source(root+"b",CODE),5000));
                HashSet<Integer> pids=new HashSet<>();pids.add(old);pids.add(protectedPid);for(ScriptWorkerClient item:capacity)pids.add(item.workerPid());
                boolean full=false;try(ScriptWorkerClient unexpected=ScriptWorkerClient.open(context,source(root+"new",CODE),1000)) { }
                catch(IOException expected){full=true;}
                require(full&&pids.size()==4&&call(protectedClient,HOME).getInt("count")==2,
                        "worker_capacity_is_bounded_without_evicting_active_sources",passed);
            } finally {for(ScriptWorkerClient item:capacity)item.close();}
            AtomicReference<Throwable> cancelled=new AtomicReference<>();
            Thread search=new Thread(()->{try{looping.call(SEARCH,new JSONArray(),10000);}catch(Throwable error){cancelled.set(error);}});
            search.start();
            try {
                try(ScriptDatabase db=new ScriptDatabase(context)) {
                    ScriptStorage storage=db.source(root+"loop");long limit=android.os.SystemClock.elapsedRealtime()+3000;
                    while(!storage.get("host","loop").equals("entered")) {
                        if(android.os.SystemClock.elapsedRealtime()>limit)throw new AssertionError("Script loop did not start");Thread.sleep(10);
                    }
                }
                looping.cancel();search.join(4000);
                require(!search.isAlive()&&cancelled.get() instanceof IOException&&call(protectedClient,PROXY).getInt("count")==2&&protectedClient.workerPid()==protectedPid,
                        "cancelled_native_loop_keeps_other_source_and_proxy_state_alive",passed);
            } finally {if(search.isAlive()){looping.cancel();search.join(6000);}}
            JSONObject restarted=call(looping,HOME);
            require(looping.workerPid()!=old&&restarted.getInt("count")==1&&restarted.getInt("inits")==2,
                    "next_call_reinitializes_new_generation_after_confirmed_worker_death",passed);
            boolean timed=false;
            try{looping.call(SEARCH,new JSONArray(),500);}catch(java.net.SocketTimeoutException expected){timed=true;}
            require(timed&&call(protectedClient,PROXY).getInt("count")==2,"hard_deadline_kills_only_the_owned_worker",passed);
            require(call(looping,HOME).getInt("inits")==3,"acknowledged_storage_survives_repeated_real_worker_death",passed);
        }
    }
    private static void destroyAndStaleControl(Context context,String root,Consumer<String> passed) throws Exception {
        ScriptWorkerClient before=ScriptWorkerClient.open(context,source(root+"a",CODE),5000);
        ScriptProcess previous=process(before);Messenger stale=remote(previous);String nonce=previous.generation;before.close();
        try(ScriptWorkerClient after=ScriptWorkerClient.open(context,source(root+"new",CODE),5000)) {
            int pid=after.workerPid();Message message=Message.obtain(null,ScriptWorkerService.TERMINATE);message.getData().putString("generation",nonce);
            try{stale.send(message);}catch(android.os.RemoteException ignored) { }
            require(call(after,HOME).getInt("count")==1&&after.workerPid()==pid,"late_control_from_old_service_cannot_kill_reused_worker_process",passed);
        }
        ScriptWorkerClient destroy=ScriptWorkerClient.open(context,source(root+"destroy","export default {home(){return {};},destroy(){while(true){}}}"),5000);
        int destroyed=destroy.workerPid();destroy.close();
        try(ScriptWorkerClient recovered=ScriptWorkerClient.open(context,source(root+"new",CODE),5000)) {
            require(recovered.workerPid()!=destroyed&&call(recovered,HOME).getInt("count")==1,
                    "looping_destroy_is_terminated_before_worker_slot_reuse",passed);
        }
    }
    private static ScriptProcess process(ScriptWorkerClient client)throws Exception {
        java.lang.reflect.Field field=ScriptWorkerClient.class.getDeclaredField("process");field.setAccessible(true);return (ScriptProcess)field.get(client);
    }
    private static Messenger remote(ScriptProcess process)throws Exception {
        java.lang.reflect.Field field=ScriptProcess.class.getDeclaredField("remote");field.setAccessible(true);return (Messenger)field.get(process);
    }
    private static void require(boolean value,String name,Consumer<String> passed){if(!value)throw new AssertionError(name);passed.accept(name);}
}
