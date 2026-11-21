import Foundation
@main struct Checks {
 @MainActor static func settle(_ condition:()->Bool) async {for _ in 0..<10000 {if condition(){return};await Task.yield()}}
 @MainActor static func main() async {
  var failed=0
  func check(_ name:String,_ okay:Bool) {print("\(okay ? "PASS" : "FAIL") \(name)");if !okay{failed+=1}}
  do {
   let e=Engine();e.install("synthetic-a","a");var called=false
   e.start {_ in called=true};await settle{!StreamCallTokenFetch.gates.isEmpty}
   let gate=StreamCallTokenFetch.gates.removeFirst()
   e.install("synthetic-b","b");gate.resume(returning:"late-a");await settle{called}
   check("http_old_user_cannot_overwrite_replacement",e.credentials?.userId=="synthetic-b" && e.credentials?.token=="b")
  }
  do {
   let e=Engine();e.install("synthetic-a","a");var called=false
   e.start {_ in called=true};await settle{!StreamCallTokenFetch.gates.isEmpty}
   let gate=StreamCallTokenFetch.gates.removeFirst()
   try! e.setToken("manual-newer");gate.resume(returning:"http-older");await settle{called}
   check("manual_revision_survives_old_http",e.credentials?.token=="manual-newer")
  }
  do {
   let e=Engine();e.install("synthetic-a","a",url:nil);var called=false
   e.start {_ in called=true};await settle{!e.pendingTokenWaiters.isEmpty}
   let finish=e.pendingTokenWaiters[0]
   e.install("synthetic-b","b",url:nil);_ = e.ledger.join("default:replacement")
   finish(nil);await settle{called}
   check("old_nil_cannot_end_new_call",e.ledger.live?.cid=="default:replacement")
  }

  do {
   let e=Engine();e.install("synthetic-a","a");var responses:[String?]=[]
   e.start {responses.append($0)};await settle{!StreamCallTokenFetch.gates.isEmpty}
   let gate=StreamCallTokenFetch.gates.removeFirst();e.install("synthetic-a","a");gate.resume(returning:"obsolete");await settle{responses.count==1}
   check("same_user_relogin_has_distinct_session",e.credentials?.token=="a" && responses.count==1 && responses[0]==nil)
  }
  do {
   let e=Engine();e.install("synthetic-a","a");var responses:[String?]=[]
   e.start {responses.append($0)};await settle{!StreamCallTokenFetch.gates.isEmpty}
   let gate=StreamCallTokenFetch.gates.removeFirst();e.install("synthetic-b","b");gate.resume(returning:nil);await Task.yield()
   check("stale_http_failure_emits_no_page_request",e.requestIds.isEmpty && responses.count==1)
  }
  do {
   let e=Engine();e.install("synthetic-a","a");var responses:[String?]=[]
   e.start {responses.append($0)};e.start {responses.append($0)};await settle{StreamCallTokenFetch.gates.count==2}
   let gates=StreamCallTokenFetch.gates;StreamCallTokenFetch.gates=[]
   try! e.setToken("manual-newer");gates.forEach{$0.resume(returning:"obsolete")};await Task.yield()
   check("all_same_session_waiters_complete_once",responses.count==2 && responses.allSatisfy{$0=="manual-newer"} && e.credentials?.token=="manual-newer")
  }
  do {
   let e=Engine();e.install("synthetic-a","a",url:nil);var called=false
   e.start {_ in called=true};await settle{!e.requestIds.isEmpty};let id=e.requestIds[0]
   e.install("synthetic-b","b",url:nil)
   var stale=false;do{try e.setToken("obsolete",requestId:id)}catch StreamCallError.tokenRequestExpired{stale=true}catch{}
   check("stale_request_id_cannot_target_new_user",stale && called && e.credentials?.token=="b")
   var unknown=false;do{try e.setToken("obsolete",requestId:"unknown")}catch StreamCallError.tokenRequestExpired{unknown=true}catch{}
   check("unknown_request_id_rejected",unknown && e.credentials?.token=="b")
  }
  do {
   let e=Engine();e.install("synthetic-a","a",url:nil);_ = e.ledger.join("default:expired");var called=false
   e.start {_ in called=true};await settle{!e.pendingTokenWaiters.isEmpty};e.pendingTokenWaiters[0](nil)
   check("true_current_expiry_ends_own_call",called && e.ledger.live==nil)
  }
  do {
   let e=Engine();e.install("synthetic-a","a",url:nil);var response:String?
   e.start {response=$0};await settle{!e.requestIds.isEmpty};let id=e.requestIds[0]
   StreamCallKeychain.fail=true;var failedSave=false
   do{try e.setToken("unpersisted",requestId:id)}catch StreamCallError.storageFailed{failedSave=true}catch{}
   check("failed_storage_does_not_release_refresh",failedSave && response==nil && !e.pendingTokenWaiters.isEmpty && e.credentials?.token=="a")
   StreamCallKeychain.fail=false;try! e.setToken("persisted",requestId:id)
   check("valid_request_completes_after_storage_recovers",response=="persisted" && StreamCallKeychain.saved?.token=="persisted")
  }
  do {
   let e=Engine();e.install("synthetic-a","a");var response:String?
   e.start {response=$0};await settle{!StreamCallTokenFetch.gates.isEmpty};let gate=StreamCallTokenFetch.gates.removeFirst()
   StreamCallKeychain.fail=true;gate.resume(returning:"unpersisted");await settle{!e.requestIds.isEmpty}
   check("http_storage_failure_falls_back_to_page",response==nil && e.credentials?.token=="a" && !e.requestIds.isEmpty)
   StreamCallKeychain.fail=false;try! e.setToken("persisted",requestId:e.requestIds[0])
  }
  do {
   let e=Engine();e.install("synthetic-a","a",url:nil);StreamVideo.pausedUsers.insert("synthetic-candidate")
   var configureResult:String?
   let attempt=Task{@MainActor in do{_ = try await e.configure(StreamCallCredentials(userId:"synthetic-candidate",token:"candidate",tokenURL:nil));configureResult="ok"}catch{configureResult="error"}}
   await settle{StreamVideo.connects["synthetic-candidate"] != nil};var response:String?
   e.startCandidate {response=$0};await settle{!e.requestIds.isEmpty}
   try! e.setToken("candidate-new",requestId:e.requestIds[0])
   check("candidate_page_reply_does_not_overwrite_active",e.credentials?.userId=="synthetic-a" && StreamCallKeychain.saved?.userId=="synthetic-a" && response=="candidate-new")
   StreamVideo.pausedUsers.remove("synthetic-candidate");StreamVideo.connects.removeValue(forKey:"synthetic-candidate")!.resume();await attempt.value
   check("configure_commits_candidates_latest_token",configureResult=="ok" && e.credentials?.token=="candidate-new" && StreamCallKeychain.saved?.token=="candidate-new")
  }
  do {
   let e=Engine();e.install("synthetic-a","a",url:nil);StreamVideo.pausedUsers.insert("synthetic-stale")
   var result:String?
   let attempt=Task{@MainActor in do{_ = try await e.configure(StreamCallCredentials(userId:"synthetic-stale",token:"candidate",tokenURL:nil));result="ok"}catch{result="error"}}
   await settle{StreamVideo.connects["synthetic-stale"] != nil};await e.logout();e.install("synthetic-b","b",url:nil)
   StreamVideo.pausedUsers.remove("synthetic-stale");StreamVideo.connects.removeValue(forKey:"synthetic-stale")!.resume();await attempt.value
   check("late_configure_cannot_commit_after_logout",result=="error" && e.credentials?.userId=="synthetic-b" && StreamCallKeychain.saved?.userId=="synthetic-b")
  }
  do {
   let e=Engine();e.install("synthetic-a","a",url:nil);StreamVideo.pauseDisconnect=true
   let logout=Task{@MainActor in await e.logout()};await settle{!StreamVideo.disconnects.isEmpty}
   StreamVideo.pauseDisconnect=false;_ = try! await e.configure(StreamCallCredentials(userId:"synthetic-b",token:"b",tokenURL:nil))
   StreamVideo.disconnects.removeFirst().resume();await logout.value
   check("late_logout_does_not_detach_new_client",e.client?.user=="synthetic-b" && e.callKit.streamVideo===e.client && e.credentials?.userId=="synthetic-b")
  }
  do {
   let e=Engine();e.install("synthetic-a","a",url:nil);StreamCallKeychain.fail=true;var failedSave=false
   do{_ = try await e.configure(StreamCallCredentials(userId:"synthetic-b",token:"b",tokenURL:nil))}catch StreamCallError.storageFailed{failedSave=true}catch{}
   StreamCallKeychain.fail=false
   check("configure_save_failure_keeps_old_session",failedSave && e.credentials?.userId=="synthetic-a" && e.client?.user=="synthetic-a")
  }
  do {
   let e=Engine();e.install("synthetic-a","a",url:nil);StreamVideo.pausedUsers.insert("synthetic-failed")
   var stale=false
   let attempt=Task{@MainActor in do{_ = try await e.configure(StreamCallCredentials(userId:"synthetic-failed",token:"candidate",tokenURL:nil))}catch StreamCallError.sessionChanged{stale=true}catch{}}
   await settle{StreamVideo.connects["synthetic-failed"] != nil};await e.logout();e.install("synthetic-b","b",url:nil)
   StreamVideo.pausedUsers.remove("synthetic-failed");StreamVideo.connects.removeValue(forKey:"synthetic-failed")!.resume(throwing:StreamCallError.connectFailed);await attempt.value
   check("stale_connect_exception_keeps_new_session",stale && e.credentials?.userId=="synthetic-b" && StreamCallKeychain.saved?.userId=="synthetic-b")
  }
  do {
   let e=Engine();e.install("synthetic-a","a",url:nil);let provider=e.provider()
   await e.logout();_ = try! await e.configure(StreamCallCredentials(userId:"synthetic-b",token:"b",tokenURL:nil))
   var calls=0;var answer:String?="unexpected";let fetches=StreamCallTokenFetch.users.count
   provider {calls+=1;answer=$0};await Task.yield()
   check("obsolete_provider_entry_completes_without_work",calls==1 && answer==nil && e.requestIds.isEmpty && StreamCallTokenFetch.users.count==fetches && e.credentials?.userId=="synthetic-b")
  }
  do {
   let e=Engine();e.install("synthetic-a","a",url:nil);StreamVideo.pausedUsers.insert("synthetic-expired-connect");var active=true;var expired=false
   let attempt=Task{@MainActor in do{_ = try await e.configure(StreamCallCredentials(userId:"synthetic-expired-connect",token:"b",tokenURL:nil),isActive:{active})}catch StreamCallError.timedOut{expired=true}catch{}}
   await settle{StreamVideo.connects["synthetic-expired-connect"] != nil};active=false
   StreamVideo.pausedUsers.remove("synthetic-expired-connect");StreamVideo.connects.removeValue(forKey:"synthetic-expired-connect")!.resume();await attempt.value
   check("expired_route_does_not_commit_after_connect",expired && e.credentials?.userId=="synthetic-a" && StreamCallKeychain.saved?.userId=="synthetic-a")
  }
  print("checks=20 failed=\(failed)");exit(failed==0 ? 0:1)
 }
}
