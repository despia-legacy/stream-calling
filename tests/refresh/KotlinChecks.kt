package despia.ext.streamcall
import kotlinx.coroutines.*
@OptIn(DelicateCoroutinesApi::class,ExperimentalCoroutinesApi::class)
fun main() {
 val dispatcher=newSingleThreadContext("owned-refresh-checks")
 var failed=0
 fun check(name:String,okay:Boolean) {println("${if(okay) "PASS" else "FAIL"} $name");if(!okay)failed++}
 try {runBlocking(dispatcher) {
  suspend fun settle(condition:()->Boolean) {withTimeout(2000) {while(!condition()) yield()}}
  run {
   val e=Engine();e.install("synthetic-a","a");val reply=async {e.start()};settle {e.fetchStarted}
   e.install("synthetic-b","b");e.gate.complete("late-a");reply.await()
   check("http_old_user_cannot_overwrite_replacement",e.credentials?.userId=="synthetic-b" && e.credentials?.token=="b")
  }
  run {
   val e=Engine();e.install("synthetic-a","a");val reply=async {e.start()};settle {e.fetchStarted}
   e.setToken("manual-newer");e.gate.complete("http-older");reply.await()
   check("manual_revision_survives_old_http",e.credentials?.token=="manual-newer")
  }
  run {
   val e=Engine();e.install("synthetic-a","a",null);val reply=async {e.start()};settle {e.tokenWaiter!=null}
   val finish=e.tokenWaiter!!
   e.install("synthetic-b","b",null);e.ledger.join("default:replacement");finish.complete(null);reply.await()
   check("old_nil_cannot_end_new_call",e.ledger.live()?.cid=="default:replacement")
  }

  run {
   val e=Engine(dispatcher);e.install("synthetic-a","a");val reply=async{e.start()};settle{e.fetchStarted}
   e.install("synthetic-a","a");e.gate.complete("obsolete");val token=reply.await()
   check("same_user_relogin_has_distinct_session",e.credentials?.token=="a" && token==null)
  }
  run {
   val e=Engine(dispatcher);e.install("synthetic-a","a");val reply=async{e.start()};settle{e.fetchStarted}
   e.install("synthetic-b","b");e.gate.complete(null);reply.await()
   check("stale_http_failure_emits_no_page_request",e.requestIds.isEmpty())
  }
  run {
   val e=Engine(dispatcher);e.install("synthetic-a","a",null)
   val first=async{e.start()};val second=async{e.start()};settle{e.requestIds.size==2}
   e.setToken("manual-newer");val results=listOf(first.await(),second.await())
   check("all_same_session_waiters_complete_once",results.all{it=="manual-newer"} && e.credentials?.token=="manual-newer")
  }
  run {
   val e=Engine(dispatcher);e.install("synthetic-a","a",null);val reply=async{e.start()};settle{e.requestIds.isNotEmpty()}
   val id=e.requestIds[0];e.install("synthetic-b","b",null);reply.await()
   check("stale_request_id_cannot_target_new_user",e.setToken("obsolete",id)=="token_request_expired" && e.credentials?.token=="b")
   check("unknown_request_id_rejected",e.setToken("obsolete","unknown")=="token_request_expired" && e.credentials?.token=="b")
  }
  run {
   val e=Engine(dispatcher);e.install("synthetic-a","a",null);e.ledger.join("default:expired")
   val reply=async{e.start()};settle{e.tokenWaiter!=null};e.tokenWaiter!!.complete(null);reply.await()
   check("true_current_expiry_ends_own_call",e.ledger.live()==null)
  }
  run {
   val e=Engine(dispatcher);e.install("synthetic-a","a",null);val reply=async{e.start()};settle{e.requestIds.isNotEmpty()};val id=e.requestIds[0]
   StreamCallCredentials.fail=true;val error=e.setToken("unpersisted",id)
   check("failed_storage_does_not_release_refresh",error=="storage_failed" && !reply.isCompleted && e.credentials?.token=="a")
   StreamCallCredentials.fail=false;e.setToken("persisted",id)
   check("valid_request_completes_after_storage_recovers",reply.await()=="persisted" && StreamCallCredentials.saved?.token=="persisted")
  }
  run {
   val e=Engine(dispatcher);e.install("synthetic-a","a");val reply=async{e.start()};settle{e.fetchStarted}
   StreamCallCredentials.fail=true;e.gate.complete("unpersisted");settle{e.requestIds.isNotEmpty()}
   check("http_storage_failure_falls_back_to_page",!reply.isCompleted && e.credentials?.token=="a")
   StreamCallCredentials.fail=false;e.setToken("persisted",e.requestIds[0]);reply.await()
  }
  run {
   val e=Engine(dispatcher);e.install("synthetic-a","a",null)
   val gate=CompletableDeferred<Result>();StreamVideo.paused["synthetic-candidate"]=gate
   val configured=CompletableDeferred<String?>();e.configure(StreamCallCredentials("synthetic-candidate","candidate",null)){code,_->configured.complete(code)}
   settle{e.client==null};val refresh=async{e.startCandidate()};settle{e.requestIds.isNotEmpty()}
   val error=e.setToken("candidate-new",e.requestIds[0])
   check("candidate_page_reply_does_not_overwrite_saved_user",error==null && e.credentials==null && StreamCallCredentials.saved?.userId=="synthetic-a" && refresh.await()=="candidate-new")
   StreamVideo.paused.remove("synthetic-candidate");gate.complete(Result.Success());val code=configured.await()
   check("configure_commits_candidates_latest_token",code==null && e.credentials?.token=="candidate-new" && StreamCallCredentials.saved?.token=="candidate-new")
  }
  run {
   val e=Engine(dispatcher);e.install("synthetic-a","a",null)
   val gate=CompletableDeferred<Result>();StreamVideo.paused["synthetic-stale"]=gate
   val configured=CompletableDeferred<String?>();e.configure(StreamCallCredentials("synthetic-stale","candidate",null)){code,_->configured.complete(code)}
   settle{e.client==null};val loggedOut=CompletableDeferred<Unit>();e.logout{_,_->loggedOut.complete(Unit)};loggedOut.await();e.install("synthetic-b","b",null)
   StreamVideo.paused.remove("synthetic-stale");gate.complete(Result.Success());val code=configured.await()
   check("late_configure_cannot_commit_after_logout",code=="session_changed" && e.credentials?.userId=="synthetic-b" && StreamVideo.installed===e.client && StreamCallCredentials.saved?.userId=="synthetic-b")
  }
  run {
   val e=Engine(dispatcher);e.install("synthetic-a","a",null);val cleanup=CompletableDeferred<Unit>();e.cleanupGate=cleanup
   val loggedOut=CompletableDeferred<Unit>();e.logout{_,_->loggedOut.complete(Unit)};settle{e.cleanupStarted}
   val configured=CompletableDeferred<String?>();e.configure(StreamCallCredentials("synthetic-b","b",null)){code,_->configured.complete(code)};configured.await()
   cleanup.complete(Unit);loggedOut.await()
   check("late_logout_does_not_detach_new_client",e.client?.user=="synthetic-b" && StreamVideo.installed===e.client && !e.client!!.removed)
  }
  run {
   val e=Engine(dispatcher);e.install("synthetic-a","a",null);StreamCallCredentials.fail=true
   val configured=CompletableDeferred<String?>();e.configure(StreamCallCredentials("synthetic-b","b",null)){code,_->configured.complete(code)}
   val error=configured.await();StreamCallCredentials.fail=false
   check("configure_save_failure_restores_old_session",error=="storage_failed" && e.credentials?.userId=="synthetic-a" && e.client?.user=="synthetic-a" && StreamVideo.installed===e.client)
  }
  run {
   val e=Engine(dispatcher);e.install("synthetic-a","a",null)
   val gate=CompletableDeferred<Result>();StreamVideo.paused["synthetic-failed"]=gate
   val configured=CompletableDeferred<String?>();e.configure(StreamCallCredentials("synthetic-failed","candidate",null)){code,_->configured.complete(code)}
   settle{e.client==null};val loggedOut=CompletableDeferred<Unit>();e.logout{_,_->loggedOut.complete(Unit)};loggedOut.await();e.install("synthetic-b","b",null)
   StreamVideo.paused.remove("synthetic-failed");gate.completeExceptionally(CancellationException("owned synthetic deferred cancellation"));val code=configured.await()
   check("stale_connect_exception_keeps_new_session",code=="session_changed" && e.credentials?.userId=="synthetic-b" && StreamVideo.installed===e.client && StreamCallCredentials.saved?.userId=="synthetic-b")
  }
 }} finally {dispatcher.close()}
 println("checks=18 failed=$failed");kotlin.system.exitProcess(if(failed==0)0 else 1)
}
