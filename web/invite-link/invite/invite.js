(() => {
  "use strict";

  const pathParts = window.location.pathname.split("/").filter(Boolean);
  const token = pathParts[0] === "invite" ? pathParts.slice(1).join("/") : "";
  const status = document.getElementById("status");
  const actions = document.getElementById("invite-actions");
  const openApp = document.getElementById("open-app");
  const copyLink = document.getElementById("copy-link");

  function showStatus(message, type = "") {
    status.textContent = message;
    status.className = `status ${type}`.trim();
  }

  async function copyInviteUrl() {
    const inviteUrl = window.location.href;
    try {
      await navigator.clipboard.writeText(inviteUrl);
    } catch {
      const temporaryInput = document.createElement("textarea");
      temporaryInput.value = inviteUrl;
      temporaryInput.setAttribute("readonly", "");
      temporaryInput.style.position = "fixed";
      temporaryInput.style.opacity = "0";
      document.body.appendChild(temporaryInput);
      temporaryInput.select();
      const copied = document.execCommand("copy");
      temporaryInput.remove();
      if (!copied) throw new Error("copy failed");
    }
    showStatus("招待リンクをコピーしました。インストール後に同じリンクを開き直してください。");
  }

  if (!token) {
    showStatus("招待情報を確認できません。トークンを含む元の招待リンクを開き直してください。", "error");
    return;
  }

  openApp.href = `urlsaver://invite/${encodeURIComponent(token)}`;
  actions.hidden = false;
  showStatus("招待リンクを確認しました。アプリは自動では開きません。下のボタンから開いてください。");
  copyLink.addEventListener("click", () => {
    copyInviteUrl().catch(() => {
      showStatus("リンクをコピーできませんでした。ブラウザーのアドレス欄からリンク全体をコピーしてください。", "error");
    });
  });
})();
