// Location: flare-web-client/src/js/login/login.js
//
// The Flare login screen. This is the ONLY file you need to edit to
// customize sign in / sign up / guest — markup, styles, and behavior
// all live here together.
//
// Contract: call mountLogin(container, { onAuthenticated }).
// You MUST call onAuthenticated(token) once a user is authenticated
// (login, register, or guest) — that's the only thing the rest of the
// SDK depends on. Everything else is yours to change freely.

const LOGIN_HTML = `
  <div style="background:#fff;border-radius:24px;padding:40px 32px;width:100%;max-width:380px;box-shadow:0 4px 32px rgba(0,0,0,0.08);">
    <div style="font-size:26px;font-weight:bold;color:#1a1a2e;text-align:center;margin-bottom:8px;">🔥 Flare Demo</div>
    <div id="auth-subtitle" style="font-size:14px;color:#888;text-align:center;margin-bottom:32px;">Sign in to continue</div>

    <div id="login-error" style="display:none;background:#fdecea;color:#c0392b;border-radius:10px;padding:12px 16px;font-size:14px;margin-bottom:16px;text-align:center;"></div>

    <div id="register-fields" style="display:none;">
      <input id="reg-first" type="text" placeholder="First Name" style="display:block;width:100%;padding:14px 16px;border:1.5px solid #e0e0e0;border-radius:12px;font-size:16px;margin-bottom:12px;box-sizing:border-box;" />
      <input id="reg-last" type="text" placeholder="Last Name" style="display:block;width:100%;padding:14px 16px;border:1.5px solid #e0e0e0;border-radius:12px;font-size:16px;margin-bottom:12px;box-sizing:border-box;" />
      <input id="reg-confirm" type="password" placeholder="Confirm Password" style="display:block;width:100%;padding:14px 16px;border:1.5px solid #e0e0e0;border-radius:12px;font-size:16px;margin-bottom:12px;box-sizing:border-box;" />
    </div>

    <input id="login-email" type="email" placeholder="Email" style="display:block;width:100%;padding:14px 16px;border:1.5px solid #e0e0e0;border-radius:12px;font-size:16px;background:#f7f7f7;margin-bottom:12px;box-sizing:border-box;" />
    <input id="login-password" type="password" placeholder="Password" style="display:block;width:100%;padding:14px 16px;border:1.5px solid #e0e0e0;border-radius:12px;font-size:16px;background:#f7f7f7;margin-bottom:12px;box-sizing:border-box;" />

    <button id="btn-submit" style="display:block;width:100%;padding:16px;border:none;border-radius:14px;font-size:16px;font-weight:bold;cursor:pointer;margin-bottom:16px;background:#8e44ad;color:#fff;">
      Sign In
    </button>

    <div style="text-align:center;font-size:14px;color:#888;">
      <span id="toggle-text">Need an account?</span>
      <a id="btn-toggle-mode" href="#" style="color:#8e44ad;font-weight:bold;text-decoration:none;">Sign Up</a>
    </div>

    <div style="display:flex;align-items:center;margin:24px 0;color:#ccc;font-size:13px;gap:8px;">
      <div style="flex:1;height:1px;background:#e0e0e0;"></div><span>or</span><div style="flex:1;height:1px;background:#e0e0e0;"></div>
    </div>

    <button id="btn-guest" style="display:block;width:100%;padding:10px;border:none;border-radius:14px;font-size:14px;cursor:pointer;background:transparent;color:#888;">
      Continue as Guest →
    </button>
  </div>
`;

export function mountLogin(container, { onAuthenticated }) {
  container.innerHTML = LOGIN_HTML;
  let isRegisterMode = false;

  const q = (sel) => container.querySelector(sel);
  const ERROR_DIV = q("#login-error");
  const showError = (msg) => { ERROR_DIV.textContent = msg; ERROR_DIV.style.display = "block"; };
  const clearError = () => { ERROR_DIV.style.display = "none"; ERROR_DIV.textContent = ""; };

  q("#btn-toggle-mode").addEventListener("click", (e) => {
    e.preventDefault();
    isRegisterMode = !isRegisterMode;
    clearError();
    q("#register-fields").style.display = isRegisterMode ? "block" : "none";
    q("#btn-submit").textContent = isRegisterMode ? "Create Account" : "Sign In";
    q("#auth-subtitle").textContent = isRegisterMode ? "Create a new account" : "Sign in to continue";
    q("#toggle-text").textContent = isRegisterMode ? "Already have an account?" : "Need an account?";
    q("#btn-toggle-mode").textContent = isRegisterMode ? "Sign In" : "Sign Up";
  });

  async function postJson(url, body) {
    const res = await fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const json = await res.json();
    if (!res.ok) throw new Error(json.error || "Request failed");
    return json;
  }

  q("#btn-submit").addEventListener("click", async () => {
    clearError();
    const email = q("#login-email").value.trim();
    const password = q("#login-password").value;
    const btn = q("#btn-submit");

    if (!email || !password) return showError("Email and password are required.");

    if (isRegisterMode) {
      const first = q("#reg-first").value.trim();
      const last = q("#reg-last").value.trim();
      const confirm = q("#reg-confirm").value;
      if (password !== confirm) return showError("Passwords do not match!");
      if (!first) return showError("First name is required!");

      btn.disabled = true; btn.textContent = "Creating...";
      try {
        const { token } = await postJson("/auth/register", { email, password, first_name: first, last_name: last });
        onAuthenticated(token);
      } catch (e) { showError(e.message); btn.disabled = false; btn.textContent = "Create Account"; }
    } else {
      btn.disabled = true; btn.textContent = "Signing In...";
      try {
        const { token } = await postJson("/auth/login", { email, password });
        onAuthenticated(token);
      } catch (e) { showError(e.message); btn.disabled = false; btn.textContent = "Sign In"; }
    }
  });

  q("#btn-guest").addEventListener("click", async () => {
    const btn = q("#btn-guest");
    btn.disabled = true; btn.textContent = "Connecting...";
    try {
      const { token } = await postJson("/auth/guest", {});
      onAuthenticated(token);
    } catch (e) {
      btn.disabled = false; btn.textContent = "Continue as Guest →";
    }
  });

  // Called by app.js after logout so buttons aren't stuck mid-state next time.
  return {
    reset() {
      const submitBtn = q("#btn-submit");
      if (submitBtn) { submitBtn.disabled = false; submitBtn.textContent = isRegisterMode ? "Create Account" : "Sign In"; }
      const guestBtn = q("#btn-guest");
      if (guestBtn) { guestBtn.disabled = false; guestBtn.textContent = "Continue as Guest →"; }
    }
  };
}