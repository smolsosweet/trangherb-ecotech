(() => {
    const widget = document.getElementById("chatbot-widget");
    if (!widget) return;

    const toggleBtn = widget.querySelector(".chatbot-toggle");
    const closeBtn = widget.querySelector(".chatbot-close");
    const panel = widget.querySelector(".chatbot-panel");
    const messages = widget.querySelector(".chatbot-messages");
    const form = widget.querySelector(".chatbot-input");
    const input = form.querySelector("input");

    const addMessage = (text, role) => {
        const msg = document.createElement("div");
        msg.className = `chatbot-message ${role}`;
        msg.textContent = text;
        messages.appendChild(msg);
        messages.scrollTop = messages.scrollHeight;
    };

    const normalize = (text) =>
        text
            .toLowerCase()
            .normalize("NFD")
            .replace(/[\u0300-\u036f]/g, "")
            .replace(/[^a-z0-9\\s]/g, " ")
            .replace(/\\s+/g, " ")
            .trim();

    const detectAudience = (text) => {
        const t = normalize(text);
        const businessKeys = [
            "doanh nghiep", "doi tac", "gia si", "dai ly", "hop dong",
            "xuat khau", "chung chi", "phan phoi", "so luong", "nha hang",
            "quan an", "chuoi", "ban le"
        ];
        const farmerKeys = [
            "benh", "sau", "sau benh", "thuoc", "pha", "phun", "ky thuat",
            "trong", "nuoi", "bon", "tuoi", "cay", "giong"
        ];

        if (businessKeys.some((k) => t.includes(k))) return "business";
        if (farmerKeys.some((k) => t.includes(k))) return "farmer";
        return "default";
    };

    const formatResponse = (audience, coreText) => {
        if (!coreText) return "";
        const cleaned = coreText.trim();
        const lowered = cleaned.toLowerCase();
        if (lowered.startsWith("dạ") || lowered.startsWith("kính thưa")) {
            return cleaned;
        }
        if (audience === "farmer") {
            return `Dạ bác ơi, ${cleaned}`;
        }
        if (audience === "business") {
            return `Kính thưa Anh/Chị, ${cleaned}`;
        }
        return `Dạ, ${cleaned}`;
    };

    const sanitizeServerText = (rawText) => {
        if (!rawText) return "";

        let text = rawText
            .replace(/\s+/g, " ")
            .replace(/([A-Za-zÀ-ỹ])(\d+\.)/g, "$1 $2")
            .trim();

        // Remove common document headings accidentally returned from DOCX chunks.
        text = text
            .replace(/\bCHƯƠNG\s*\d+[^.?!]*[.?!]?/gi, "")
            .replace(/\b\d+(?:\.\d+)*\s*Lý do chọn đề tài[.?!]?/gi, "")
            .replace(/\b\d+(?:\.\d+)*\s*[A-ZÀ-Ỹ][^.?!]{0,80}[.?!]?/g, (m) => {
                const t = m.trim();
                return /^\d+(?:\.\d+)*\s+[A-ZÀ-Ỹ\s\-–]+[.?!]?$/.test(t) ? "" : t;
            })
            .replace(/\s{2,}/g, " ")
            .trim();

        const parts = text
            .split(/(?<=[.!?…])\s+/)
            .map((s) => s.trim())
            .filter(Boolean);

        const deduped = [];
        const seen = new Set();
        for (const part of parts) {
            const key = part.toLowerCase();
            if (!seen.has(key)) {
                deduped.push(part);
                seen.add(key);
            }
        }

        return deduped.join(" ").trim();
    };

    const botReply = (userText) => {
        const audience = detectAudience(userText);
        return formatResponse(
            audience,
            "hiện hệ thống đang bận hoặc chưa phản hồi. Anh/Chị vui lòng thử lại ngay giúp mình nhé."
        );
    };

    const addTyping = () => {
        const msg = document.createElement("div");
        msg.className = "chatbot-message bot is-typing";
        msg.textContent = "Đang trả lời...";
        messages.appendChild(msg);
        messages.scrollTop = messages.scrollHeight;
        return msg;
    };

    const askServer = async (text) => {
        const response = await fetch("/api/chat", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ message: text })
        });

        if (!response.ok) {
            const detail = await response.text();
            throw new Error(detail || "Server error");
        }

        const data = await response.json();
        return data?.text || "";
    };

    const openChat = () => {
        widget.classList.add("is-open");
        toggleBtn.setAttribute("aria-label", "Đóng chatbot");
        if (!messages.hasChildNodes()) {
            addMessage("Chào bạn! Mình có thể hỗ trợ thông tin về TrangHerb EcoTech. Bạn cần hỏi gì?", "bot");
        }
        input.focus();
    };

    const closeChat = () => {
        widget.classList.remove("is-open");
        toggleBtn.setAttribute("aria-label", "Mở chatbot");
        toggleBtn.focus();
    };

    toggleBtn.addEventListener("click", () => {
        if (widget.classList.contains("is-open")) {
            closeChat();
        } else {
            openChat();
        }
    });

    closeBtn.addEventListener("click", closeChat);

    form.addEventListener("submit", (event) => {
        event.preventDefault();
        const text = input.value.trim();
        if (!text) return;

        addMessage(text, "user");
        input.value = "";

        const typing = addTyping();

        setTimeout(async () => {
            try {
                const audience = detectAudience(text);
                const reply = sanitizeServerText(await askServer(text));
                typing.remove();
                addMessage(formatResponse(audience, reply), "bot");
            } catch (err) {
                typing.remove();
                addMessage(botReply(text), "bot");
            }
        }, 250);
    });

    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && widget.classList.contains("is-open")) {
            closeChat();
        }
    });
})();
