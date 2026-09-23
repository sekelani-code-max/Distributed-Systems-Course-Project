package api;

public final class ChatPage {
    private ChatPage() { }

    public static String html() {
        return String.join("\n",
                "<!doctype html>",
                "<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Distributed Chat</title>",
                "<style>",
                "*{box-sizing:border-box}@keyframes spin{to{transform:rotate(360deg)}}body{margin:0;background:#101820;color:#e8f1f2;font:16px system-ui,sans-serif}main{max-width:820px;margin:40px auto;padding:24px}h1{color:#58d6c4}section{background:#182631;border:1px solid #29404b;border-radius:10px;padding:20px;margin-bottom:18px}input,button{border:0;border-radius:6px;padding:11px;margin:4px}input{background:#edf4f3;color:#17242b}button{background:#58d6c4;color:#102027;font-weight:700;cursor:pointer}button.secondary{background:#36515e;color:#e8f1f2}button:disabled{opacity:.7;cursor:wait}.hidden{display:none}.status{min-height:24px;color:#ffc857}.loading{display:inline-block;width:13px;height:13px;margin-right:7px;border:2px solid currentColor;border-right-color:transparent;border-radius:50%;vertical-align:-2px;animation:spin .7s linear infinite}.messages{height:420px;overflow:auto;background:#0c141b;padding:14px;border-radius:6px}.message{padding:10px 0;border-bottom:1px solid #24343c}.meta{font-size:12px;color:#8caab0}.composer{display:flex}.composer input{flex:1}.top{display:flex;justify-content:space-between;align-items:center}",
                "</style></head><body><main>",
                "<div class=\"top\"><h1>Distributed Chat</h1><button id=\"logout\" class=\"secondary hidden\">Log out</button></div>",
                "<section id=\"auth\"><h2>Sign in</h2><input id=\"username\" placeholder=\"Username\" maxlength=\"32\"><input id=\"password\" type=\"password\" placeholder=\"Password\"><br><button id=\"login\">Sign in</button><button id=\"register\" class=\"secondary\">Create account</button><div id=\"auth-status\" class=\"status\"></div></section>",
                "<section id=\"chat\" class=\"hidden\"><div class=\"top\"><h2>General</h2><button id=\"mutex\" class=\"secondary\">Request shared update</button></div><div id=\"messages\" class=\"messages\"></div><div class=\"composer\"><input id=\"text\" placeholder=\"Write a message...\" maxlength=\"2000\"><button id=\"send\">Send</button></div><div id=\"status\" class=\"status\"></div></section>",
                "</main><script>",
                "const $=id=>document.getElementById(id);let token=localStorage.getItem('chat_token');",
                "function status(value){$('status').textContent=value}function authStatus(value){$('auth-status').textContent=value}function showChat(){ $('auth').classList.add('hidden');$('chat').classList.remove('hidden');$('logout').classList.remove('hidden');loadMessages();}",
                "async function auth(path){const body={username:$('username').value.trim(),password:$('password').value};if(!body.username||!body.password){authStatus('Enter a username and password');return}const buttons=[$('login'),$('register')];const activeButton=path.endsWith('register')?$('register'):$('login');const originalLabel=activeButton.textContent;buttons.forEach(button=>button.disabled=true);activeButton.innerHTML='<span class=\"loading\"></span>'+(path.endsWith('register')?'Creating account...':'Signing in...');authStatus('Checking your details...');try{const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const data=await r.json();if(!r.ok)throw Error(data.error||'Request failed');token=data.token;localStorage.setItem('chat_token',token);showChat();}catch(error){authStatus(error.message)}finally{buttons.forEach(button=>button.disabled=false);activeButton.textContent=originalLabel}}",
                "$('login').onclick=()=>auth('/api/auth/login');$('register').onclick=()=>auth('/api/auth/register');",
                "$('logout').onclick=()=>{localStorage.removeItem('chat_token');location.reload()};",
                "let messagesLoading=false;async function loadMessages(){if(messagesLoading||!token)return;messagesLoading=true;try{const r=await fetch('/api/chat/messages?conversation_id=1',{headers:{Authorization:'Bearer '+token}});if(!r.ok){localStorage.removeItem('chat_token');location.reload();return}const data=await r.json();$('messages').innerHTML=data.messages.map(m=>'<div class=\"message\"><b>'+escapeHtml(m.sender_username)+'</b><div>'+escapeHtml(m.text)+'</div><div class=\"meta\">Lamport '+m.lamport+' · '+new Date(m.created_at).toLocaleString()+'</div></div>').join('');$('messages').scrollTop=$('messages').scrollHeight;}finally{messagesLoading=false}}",
                "function escapeHtml(value){return String(value).replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]))}",
                "$('send').onclick=async()=>{const text=$('text').value.trim();if(!text)return;const r=await fetch('/api/chat/send',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:JSON.stringify({conversation_id:1,text})});if(!r.ok){const e=await r.json();status(e.error||'Could not send');return}$('text').value='';loadMessages()};",
                "$('text').onkeydown=e=>{if(e.key==='Enter')$('send').click()};$('mutex').onclick=async()=>{const r=await fetch('/api/mutex/request',{method:'POST',headers:{Authorization:'Bearer '+token}});const d=await r.json();status(d.message||d.error||'Request sent')};",
                "if(token)showChat();setInterval(()=>{if(token)loadMessages()},3000);",
                "</script></body></html>");
    }
}
