'use strict';

let accessToken = localStorage.getItem('cashin_access_token') || '';
let currentCompany = null;
let dashboardInitialized = false;

let allTransactions = [];
let filteredTransactions = [];
let searchQuery = '';
let filterType = '';
let filterProvider = '';
let sortBy = 'timestamp';
let sortOrder = 'desc';
let ws = null;
let wsRetryCount = 0;
let selectedReportType = 'daily';
let selectedCompareMode = 'none';

window.allTransactions = allTransactions;

const CURRENCY_SYMBOLS = {
    USD: '$', EUR: 'EUR ', GBP: 'GBP ', KES: 'KSh ', SLSH: 'SLSH ', ETB: 'Br ', DJF: 'Fdj '
};

function setAuthMessage(message, type = 'error') {
    const el = document.getElementById('auth-message');
    if (!el) return;
    el.classList.remove('hidden', 'success', 'error');
    el.classList.add(type === 'success' ? 'success' : 'error');
    el.textContent = message;
}

function clearAuthMessage() {
    const el = document.getElementById('auth-message');
    if (!el) return;
    el.classList.add('hidden');
    el.textContent = '';
}

function showView(view) {
    const landing = document.getElementById('landing-view');
    const auth = document.getElementById('auth-view');
    const dashboard = document.getElementById('dashboard-app');

    landing.classList.add('hidden');
    auth.classList.add('hidden');
    dashboard.classList.add('hidden');

    if (view === 'landing') landing.classList.remove('hidden');
    if (view === 'auth') auth.classList.remove('hidden');
    if (view === 'dashboard') dashboard.classList.remove('hidden');
}

function showAuthTab(tab) {
    const loginForm = document.getElementById('login-form');
    const signupForm = document.getElementById('signup-form');
    const loginTab = document.getElementById('tab-login-btn');
    const signupTab = document.getElementById('tab-signup-btn');

    loginTab.classList.toggle('active', tab === 'login');
    signupTab.classList.toggle('active', tab === 'signup');
    loginForm.classList.toggle('hidden', tab !== 'login');
    signupForm.classList.toggle('hidden', tab !== 'signup');
}

async function readApiPayload(response) {
    const raw = await response.text();
    if (!raw) return {};

    try {
        return JSON.parse(raw);
    } catch {
        return { detail: raw };
    }
}

async function apiFetch(url, options = {}) {
    const headers = new Headers(options.headers || {});
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`);

    const fetchOpts = { credentials: 'include', ...options, headers };
    const response = await fetch(url, fetchOpts);

    if (response.status === 401 || response.status === 403) {
        if (url !== '/api/auth/login' && url !== '/api/auth/signup' && url !== '/api/auth/me') {
            logoutToAuth('Your session has expired. Please login again.');
        }
    }

    return response;
}

window.apiFetch = apiFetch;

function logoutToAuth(message = '') {
    accessToken = '';
    currentCompany = null;
    dashboardInitialized = false;
    allTransactions = [];
    window.allTransactions = [];
    if (pollInterval) {
        clearInterval(pollInterval);
        pollInterval = null;
    }
    localStorage.removeItem('cashin_access_token');
    if (ws) {
        ws.close();
        ws = null;
    }
    if (message) setAuthMessage(message, 'error');
    showAuthTab('login');
    showView('auth');
}

function setCompanyChip() {
    const chip = document.getElementById('company-chip');
    if (!chip || !currentCompany) return;
    chip.textContent = `${currentCompany.company_name} (${currentCompany.company_code})`;
}

async function bootstrapAuth() {
    bindAuthUI();

    try {
        const res = await apiFetch('/api/auth/me');
        if (!res.ok) throw new Error('Session invalid');
        const payload = await readApiPayload(res);
        if (payload.access_token) {
            accessToken = payload.access_token;
            localStorage.setItem('cashin_access_token', accessToken);
        }
        currentCompany = payload.company;
        setCompanyChip();
        showView('dashboard');
        initDashboard();
    } catch {
        if (accessToken) {
            localStorage.removeItem('cashin_access_token');
            accessToken = '';
        }
        showView('landing');
    }
}

function bindAuthUI() {
    const goLoginBtn = document.getElementById('go-login-btn');
    const goSignupBtn = document.getElementById('go-signup-btn');
    const backLandingLogin = document.getElementById('back-to-landing-login');
    const backLandingSignup = document.getElementById('back-to-landing-signup');
    const loginForm = document.getElementById('login-form');
    const signupForm = document.getElementById('signup-form');
    const signupSuccess = document.getElementById('signup-success');
    const generatedCode = document.getElementById('generated-company-code');

    document.getElementById('tab-login-btn').addEventListener('click', () => showAuthTab('login'));
    document.getElementById('tab-signup-btn').addEventListener('click', () => showAuthTab('signup'));

    goLoginBtn.addEventListener('click', () => {
        clearAuthMessage();
        signupSuccess.classList.add('hidden');
        showAuthTab('login');
        showView('auth');
    });

    goSignupBtn.addEventListener('click', () => {
        clearAuthMessage();
        signupSuccess.classList.add('hidden');
        showAuthTab('signup');
        showView('auth');
    });

    backLandingLogin.addEventListener('click', () => showView('landing'));
    backLandingSignup.addEventListener('click', () => showView('landing'));

    loginForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        clearAuthMessage();

        const companyCode = document.getElementById('login-company-code').value.trim().toUpperCase();
        const password = document.getElementById('login-password').value;

        try {
            const res = await fetch('/api/auth/login', {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ company_code: companyCode, password })
            });

            const payload = await readApiPayload(res);
            if (!res.ok) {
                setAuthMessage(payload.detail || 'Login failed');
                return;
            }

            accessToken = payload.access_token;
            currentCompany = payload.company;
            localStorage.setItem('cashin_access_token', accessToken);
            setCompanyChip();
            showView('dashboard');
            initDashboard();
        } catch (error) {
            setAuthMessage(error.message || 'Login failed');
        }
    });

    signupForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        clearAuthMessage();
        signupSuccess.classList.add('hidden');

        const body = {
            company_name: document.getElementById('signup-company-name').value.trim(),
            city: document.getElementById('signup-city').value.trim(),
            initial_subscription_plan: document.getElementById('signup-plan').value,
            password: document.getElementById('signup-password').value,
            status: document.getElementById('signup-status').value
        };



        try {
            const res = await fetch('/api/auth/signup', {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });

            const payload = await readApiPayload(res);
            if (!res.ok) {
                setAuthMessage(payload.detail || 'Signup failed');
                return;
            }

            generatedCode.textContent = payload.company_code;
            signupSuccess.classList.remove('hidden');
            setAuthMessage(`Company created. Your code is ${payload.company_code}.`, 'success');

            document.getElementById('login-company-code').value = payload.company_code;
            showAuthTab('login');
        } catch (error) {
            setAuthMessage(error.message || 'Signup failed');
        }
    });

    const logoutBtn = document.getElementById('logout-btn');
    if (logoutBtn) {
        logoutBtn.addEventListener('click', () => {
            logoutToAuth('Logged out successfully.');
        });
    }
}

function formatAmount(amount, currency) {
    const sym = CURRENCY_SYMBOLS[currency] || `${currency || ''} `;
    const num = Number(amount || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return `${sym}${num}`.trim();
}
window.formatAmount = formatAmount;

function updateClock() {
    const now = new Date();
    const liveTimeEl = document.getElementById('live-time');
    if (!liveTimeEl) return;
    liveTimeEl.textContent = now.toLocaleString('en-US', {
        month: 'long', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit'
    });
}

function getProviderClass(provider) {
    if (!provider) return 'default';
    const p = provider.toLowerCase();
    if (p.includes('evc')) return 'evc';
    if (p.includes('edahab')) return 'edahab';
    if (p.includes('zaad')) return 'zaad';
    if (p.includes('sahal')) return 'sahal';
    return 'default';
}

function getProviderLogo(provider) {
    if (!provider) return null;
    const p = provider.toLowerCase();
    if (p.includes('zaad')) return '/zaad_logo.png';
    if (p.includes('evc')) return '/evc_logo.png';
    if (p.includes('edahab')) return '/edahab_logo.png';
    if (p.includes('sahal')) return '/sahal_logo.png';
    return null;
}

let latestTxnId = null;

function createTransactionCard(txn, isLatest = false) {
    const isReceived = txn.type === 'Received';
    const typeClass = isReceived ? 'received' : 'sent';
    const amountSign = isReceived ? '+' : '-';

    const iconClass = getProviderClass(txn.provider);
    const logoUrl = getProviderLogo(txn.provider);
    const iconHtml = logoUrl ? `<img src="${logoUrl}" alt="${txn.provider}">` : (txn.provider ? txn.provider.charAt(0).toUpperCase() : 'T');

    const dateStr = new Date(txn.timestamp).toLocaleString('en-US', {
        month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
    });

    const primaryName = isReceived ? (txn.sender || 'Unknown') : (txn.receiver || 'Unknown');
    const primaryNumber = isReceived ? txn.sender_number : txn.receiver_number;
    const latestClass = isLatest ? ' txn-latest' : '';

    return `
        <div class="txn-card${latestClass}" onclick="showTxnDetails(${txn.id})">
            ${isLatest ? '<div class="txn-new-badge">NEW</div>' : ''}
            <div class="txn-icon ${iconClass}">${iconHtml}</div>
            <div class="txn-details">
                <div class="txn-sender">
                    ${primaryName}
                    ${primaryNumber ? `<span class="txn-number">(${primaryNumber})</span>` : ''}
                </div>
                <div class="txn-meta">
                    <span>${dateStr}</span>
                    ${txn.provider ? `<span>• ${txn.provider}</span>` : ''}
                    ${txn.transaction_id ? `<span>• ID: ${txn.transaction_id}</span>` : ''}
                </div>
            </div>
            <div class="txn-amount-col">
                <div class="txn-amount ${typeClass}">${amountSign} ${formatAmount(txn.amount, txn.currency)}</div>
            </div>
        </div>
    `;
}

function renderLedger() {
    const ledgerList = document.getElementById('ledger-list');
    const ledgerEmpty = document.getElementById('ledger-empty');
    if (!ledgerList || !ledgerEmpty) return;

    if (filteredTransactions.length === 0) {
        ledgerList.classList.add('hidden');
        ledgerEmpty.classList.remove('hidden');
    } else {
        ledgerEmpty.classList.add('hidden');
        ledgerList.classList.remove('hidden');
        ledgerList.innerHTML = filteredTransactions.map((txn, i) => createTransactionCard(txn, i === 0 && txn.id === latestTxnId)).join('');
    }
}

function renderDashboardRecent() {
    const dashboardList = document.getElementById('dashboard-recent-list');
    const dashboardEmpty = document.getElementById('dashboard-empty');
    if (!dashboardList || !dashboardEmpty) return;

    const recent = [...allTransactions].sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp)).slice(0, 5);
    if (recent.length === 0) {
        dashboardEmpty.classList.remove('hidden');
        dashboardList.innerHTML = '';
    } else {
        dashboardEmpty.classList.add('hidden');
        dashboardList.innerHTML = recent.map((txn, i) => createTransactionCard(txn, i === 0 && txn.id === latestTxnId)).join('');
    }
}

function updateMetrics() {
    const totalCount = document.getElementById('stat-total-count');
    const totalReceivedEl = document.getElementById('stat-total-received');
    const totalSentEl = document.getElementById('stat-total-sent');
    const netEl = document.getElementById('stat-net-balance');
    if (!totalCount || !totalReceivedEl || !totalSentEl || !netEl) return;

    totalCount.textContent = allTransactions.length;

    let received = 0;
    let sent = 0;
    allTransactions.forEach((t) => {
        if (t.type === 'Received') received += Number(t.amount || 0);
        else sent += Number(t.amount || 0);
    });

    totalReceivedEl.textContent = formatAmount(received, 'SLSH');
    totalSentEl.textContent = formatAmount(sent, 'SLSH');

    const net = received - sent;
    netEl.textContent = `${net >= 0 ? '+' : '-'} ${formatAmount(Math.abs(net), 'SLSH')}`;
    netEl.className = `metric-value ${net >= 0 ? 'text-success' : 'text-danger'}`;
}

function applyFilters() {
    const query = searchQuery.toLowerCase();

    filteredTransactions = allTransactions.filter((txn) => {
        const matchesSearch =
            (txn.sender && txn.sender.toLowerCase().includes(query)) ||
            (txn.transaction_id && String(txn.transaction_id).toLowerCase().includes(query));
        const matchesType = filterType === '' || txn.type === filterType;
        const matchesProvider = filterProvider === '' || txn.provider === filterProvider;
        return matchesSearch && matchesType && matchesProvider;
    });

    filteredTransactions.sort((a, b) => {
        let valA;
        let valB;

        if (sortBy === 'timestamp') {
            valA = new Date(a.timestamp).getTime();
            valB = new Date(b.timestamp).getTime();
        } else if (sortBy === 'amount') {
            valA = Number(a.amount || 0);
            valB = Number(b.amount || 0);
        } else {
            valA = (a[sortBy] || '').toString();
            valB = (b[sortBy] || '').toString();
        }

        if (valA < valB) return sortOrder === 'asc' ? -1 : 1;
        if (valA > valB) return sortOrder === 'asc' ? 1 : -1;
        return 0;
    });

    renderLedger();
    renderDashboardRecent();
}

function getPeriodKey(date, type) {
    const d = new Date(date);
    if (type === 'daily') return d.toISOString().slice(0, 10);
    if (type === 'weekly') {
        const weekStart = new Date(d);
        weekStart.setDate(d.getDate() - d.getDay());
        return `W-${weekStart.toISOString().slice(0, 10)}`;
    }
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function renderReport() {
    const reportChart = document.getElementById('report-chart');
    const comparisonSummary = document.getElementById('comparison-summary');
    const summaryContent = document.getElementById('summary-content');

    const totalReceivedEl = document.getElementById('report-total-received');
    const totalSentEl = document.getElementById('report-total-sent');
    const netEl = document.getElementById('report-net-change');

    const groups = {};
    allTransactions.forEach((t) => {
        const key = getPeriodKey(t.timestamp, selectedReportType);
        if (!groups[key]) groups[key] = { received: 0, sent: 0 };
        if (t.type === 'Received') groups[key].received += Number(t.amount || 0);
        else groups[key].sent += Number(t.amount || 0);
    });

    const keys = Object.keys(groups).sort((a, b) => b.localeCompare(a));
    if (keys.length === 0) {
        summaryContent.innerHTML = '<div class="empty-state"><p>No report data available.</p></div>';
        reportChart.innerHTML = '';
        comparisonSummary.classList.add('hidden');
        totalReceivedEl.textContent = formatAmount(0, 'SLSH');
        totalSentEl.textContent = formatAmount(0, 'SLSH');
        netEl.textContent = formatAmount(0, 'SLSH');
        return;
    }

    const totals = keys.reduce((acc, key) => {
        acc.received += groups[key].received;
        acc.sent += groups[key].sent;
        return acc;
    }, { received: 0, sent: 0 });

    totalReceivedEl.textContent = formatAmount(totals.received, 'SLSH');
    totalSentEl.textContent = formatAmount(totals.sent, 'SLSH');
    const net = totals.received - totals.sent;
    netEl.textContent = `${net >= 0 ? '+' : '-'} ${formatAmount(Math.abs(net), 'SLSH')}`;
    netEl.className = `metric-value ${net >= 0 ? 'text-success' : 'text-danger'}`;

    const chartKeys = keys.slice(0, 10).reverse();
    const maxValue = Math.max(...chartKeys.map((k) => groups[k].received + groups[k].sent), 1);
    reportChart.innerHTML = chartKeys.map((key) => {
        const data = groups[key];
        const total = data.received + data.sent;
        const height = Math.round((total / maxValue) * 100);
        return `<div class="chart-column"><div class="chart-column-label">${key}</div><div class="chart-bar-outer"><div class="chart-bar" style="height:${height}%;background:var(--primary);"></div></div><div class="chart-column-value">${formatAmount(total, 'SLSH')}</div></div>`;
    }).join('');

    summaryContent.innerHTML = keys.map((key) => {
        const data = groups[key];
        const periodNet = data.received - data.sent;
        return `<div class="summary-card"><h4>${key}</h4><div class="summary-row"><span>Received</span><span class="text-success">+${formatAmount(data.received, 'SLSH')}</span></div><div class="summary-row"><span>Sent</span><span class="text-danger">-${formatAmount(data.sent, 'SLSH')}</span></div><div class="summary-row total"><span>Net</span><span class="${periodNet >= 0 ? 'text-success' : 'text-danger'}">${periodNet >= 0 ? '+' : '-'}${formatAmount(Math.abs(periodNet), 'SLSH')}</span></div></div>`;
    }).join('');

    if (selectedCompareMode === 'previous' && keys.length > 1) {
        const latest = groups[keys[0]];
        const previous = groups[keys[1]];
        const diffNet = (latest.received - latest.sent) - (previous.received - previous.sent);
        comparisonSummary.classList.remove('hidden');
        comparisonSummary.innerHTML = `<div class="comparison-card"><h4>Comparison</h4><div class="comparison-row"><span>Latest:</span><strong>${keys[0]}</strong></div><div class="comparison-row"><span>Previous:</span><strong>${keys[1]}</strong></div><div class="comparison-row"><span>Net Change:</span><strong class="${diffNet >= 0 ? 'text-success' : 'text-danger'}">${diffNet >= 0 ? '+' : '-'} ${formatAmount(Math.abs(diffNet), 'SLSH')}</strong></div></div>`;
    } else {
        comparisonSummary.classList.add('hidden');
        comparisonSummary.innerHTML = '';
    }
}

function renderSummary() { renderReport(); }
window.renderSummary = renderSummary;

function showToast(txn) {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const isReceived = txn.type === 'Received';
    const toast = document.createElement('div');
    toast.className = `toast ${isReceived ? 'received' : 'sent'}`;

    const sign = isReceived ? '+' : '-';
    const displayAmount = formatAmount(txn.amount, txn.currency);
    const name = isReceived ? (txn.sender || 'Unknown') : (txn.receiver || 'Unknown');
    const providerName = txn.provider || '';
    const logoUrl = getProviderLogo(txn.provider);
    const iconHtml = logoUrl
        ? `<img src="${logoUrl}" alt="${providerName}" style="width:28px;height:28px;object-fit:contain;">`
        : `<span style="font-size:20px;">${isReceived ? '📥' : '📤'}</span>`;

    toast.innerHTML = `
        <div class="toast-icon">${iconHtml}</div>
        <div class="toast-body">
            <div class="toast-title">${name}</div>
            <div class="toast-subtitle">
                <span style="font-size:15px;font-weight:700;color:${isReceived ? 'var(--success)' : 'var(--danger)'}">${sign}${displayAmount}</span>
                ${providerName ? `<span>${providerName}</span>` : ''}
            </div>
        </div>
        <button onclick="this.parentElement.remove()" style="background:none;border:none;cursor:pointer;color:var(--text-muted);font-size:18px;line-height:1;padding:0 0 0 8px;">✕</button>
    `;

    // Browser notification if permission granted
    if (Notification && Notification.permission === 'granted') {
        new Notification(`${isReceived ? '📥 Received' : '📤 Sent'} — ${providerName}`, {
            body: `${name}: ${sign}${displayAmount}`,
            icon: logoUrl || '/logo.png',
            tag: String(txn.id || Date.now())
        });
    }

    container.appendChild(toast);
    // Slide out animation then remove
    setTimeout(() => {
        toast.style.animation = 'toast-out 0.4s ease forwards';
        setTimeout(() => toast.remove(), 400);
    }, 5000);
}
window.showToast = showToast;

function showTxnDetails(txnId) {
    const txn = allTransactions.find((t) => Number(t.id) === Number(txnId));
    if (!txn) return;

    const modal = document.getElementById('details-modal');
    const body = document.getElementById('modal-body-content');
    const dateStr = new Date(txn.timestamp).toLocaleString();

    body.innerHTML = `
        <div class="modal-detail-row"><div class="modal-detail-label">Type</div><div class="modal-detail-value">${txn.type}</div></div>
        <div class="modal-detail-row"><div class="modal-detail-label">Amount</div><div class="modal-detail-value">${formatAmount(txn.amount, txn.currency)}</div></div>
        <div class="modal-detail-row"><div class="modal-detail-label">Provider</div><div class="modal-detail-value">${txn.provider || 'N/A'}</div></div>
        <div class="modal-detail-row"><div class="modal-detail-label">Transaction ID</div><div class="modal-detail-value">${txn.transaction_id || 'N/A'}</div></div>
        <div class="modal-detail-row"><div class="modal-detail-label">Sender</div><div class="modal-detail-value">${txn.sender || 'N/A'}</div></div>
        <div class="modal-detail-row"><div class="modal-detail-label">Receiver</div><div class="modal-detail-value">${txn.receiver || 'N/A'}</div></div>
        <div class="modal-detail-row"><div class="modal-detail-label">Date</div><div class="modal-detail-value">${dateStr}</div></div>
        <div style="margin-top:8px;"><div class="modal-detail-label">Raw SMS</div><div class="modal-raw-sms">${txn.raw_sms || ''}</div></div>
    `;

    modal.classList.remove('hidden');
}

function hideTxnDetails() {
    const modal = document.getElementById('details-modal');
    modal.classList.add('hidden');
}
window.showTxnDetails = showTxnDetails;

function handleNewTransaction(txn) {
    if (txn.event === 'cleared') {
        allTransactions = [];
        window.allTransactions = allTransactions;
        latestTxnId = null;
        applyFilters();
        updateMetrics();
        renderSummary();
        return;
    }

    const duplicate = allTransactions.some((t) => (t.id && t.id === txn.id) || (txn.transaction_id && t.transaction_id === txn.transaction_id));
    if (duplicate) return;

    allTransactions.unshift(txn);
    window.allTransactions = allTransactions;
    latestTxnId = txn.id;
    applyFilters();
    updateMetrics();
    renderSummary();
    showToast(txn);
}
window.handleNewTransaction = handleNewTransaction;

async function fetchTransactions() {
    const ledgerLoader = document.getElementById('ledger-loader');
    const ledgerEmpty = document.getElementById('ledger-empty');
    const ledgerList = document.getElementById('ledger-list');
    const dashboardList = document.getElementById('dashboard-recent-list');

    if (ledgerLoader) ledgerLoader.classList.remove('hidden');
    if (ledgerEmpty) ledgerEmpty.classList.add('hidden');
    if (ledgerList) ledgerList.classList.add('hidden');
    if (dashboardList) dashboardList.innerHTML = '';

    try {
        const res = await apiFetch('/api/transactions');
        if (res.ok) {
            allTransactions = await res.json();
            window.allTransactions = allTransactions;
            applyFilters();
            updateMetrics();
            renderSummary();
        }
    } catch (error) {
        console.error('Failed to fetch transactions', error);
    } finally {
        if (ledgerLoader) ledgerLoader.classList.add('hidden');
    }
}

let pollInterval = null;

async function silentFetchTransactions() {
    try {
        const res = await apiFetch('/api/transactions');
        if (res.ok) {
            const latestTxns = await res.json();
            const oldIds = new Set(allTransactions.map(t => t.id));
            const newTxns = latestTxns.filter(t => !oldIds.has(t.id));

            if (newTxns.length > 0) {
                allTransactions = latestTxns;
                window.allTransactions = allTransactions;
                latestTxnId = newTxns[0].id;

                applyFilters();
                updateMetrics();
                renderSummary();

                newTxns.slice(0, 5).forEach(txn => showToast(txn));
            } else if (latestTxns.length !== allTransactions.length) {
                allTransactions = latestTxns;
                window.allTransactions = allTransactions;
                applyFilters();
                updateMetrics();
                renderSummary();
            }
        }
    } catch (e) {
        // silent catch during polling
    }
}

function startPollingFallback() {
    if (!pollInterval) {
        pollInterval = setInterval(silentFetchTransactions, 3000);
    }
}

function connectWebSocket() {
    if (!accessToken) return;
    const pulse = document.getElementById('connection-pulse');
    const connText = document.getElementById('connection-text');

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws?token=${encodeURIComponent(accessToken)}`;

    try {
        ws = new WebSocket(wsUrl);
    } catch (e) {
        startPollingFallback();
        return;
    }

    ws.onopen = () => {
        wsRetryCount = 0;
        if (pulse) pulse.className = 'pulse-indicator connected';
        if (connText) {
            connText.textContent = 'Live - Connected';
            connText.style.color = 'var(--success)';
        }
    };

    ws.onmessage = (event) => {
        const txn = JSON.parse(event.data);
        handleNewTransaction(txn);
    };

    ws.onclose = () => {
        if (pulse) pulse.className = 'pulse-indicator connected';
        if (connText) {
            connText.textContent = 'Live (Auto-Sync)';
            connText.style.color = 'var(--success)';
        }
        startPollingFallback();

        const retryDelay = Math.min(2000 * (2 ** wsRetryCount), 30000);
        wsRetryCount += 1;
        setTimeout(connectWebSocket, retryDelay);
    };

    ws.onerror = () => {
        startPollingFallback();
        if (ws) ws.close();
    };
}

function bindDashboardEvents() {
    const navBtns = document.querySelectorAll('.nav-btn');
    const tabContents = document.querySelectorAll('.tab-content');
    const pageTitle = document.getElementById('page-title');

    navBtns.forEach((btn) => {
        btn.addEventListener('click', () => {
            navBtns.forEach((b) => b.classList.remove('active'));
            btn.classList.add('active');
            tabContents.forEach((tc) => tc.classList.remove('active'));
            const targetId = btn.getAttribute('data-target');
            document.getElementById(targetId).classList.add('active');
            pageTitle.textContent = btn.getAttribute('data-title');
        });
    });

    const searchInput = document.getElementById('search-input');
    const providerFilter = document.getElementById('provider-filter');
    const sortBySelect = document.getElementById('sort-by');
    const sortOrderBtn = document.getElementById('sort-order-btn');
    const typeFilterBtns = document.querySelectorAll('.filter-btn[data-type]');
    const resetBtn = document.getElementById('reset-btn');
    const generateReportBtn = document.getElementById('generate-report-btn');
    const reportTypeSelect = document.getElementById('report-type-select');
    const compareTypeSelect = document.getElementById('compare-type-select');

    searchInput.addEventListener('input', (event) => {
        searchQuery = event.target.value;
        applyFilters();
    });

    providerFilter.addEventListener('change', (event) => {
        filterProvider = event.target.value;
        applyFilters();
    });

    sortBySelect.addEventListener('change', (event) => {
        sortBy = event.target.value;
        applyFilters();
    });

    sortOrderBtn.addEventListener('click', () => {
        sortOrder = sortOrder === 'desc' ? 'asc' : 'desc';
        applyFilters();
    });

    typeFilterBtns.forEach((btn) => {
        btn.addEventListener('click', () => {
            typeFilterBtns.forEach((b) => b.classList.remove('active'));
            btn.classList.add('active');
            filterType = btn.getAttribute('data-type');
            applyFilters();
        });
    });

    resetBtn.addEventListener('click', () => {
        searchInput.value = '';
        providerFilter.value = '';
        sortBySelect.value = 'timestamp';
        searchQuery = '';
        filterProvider = '';
        sortBy = 'timestamp';
        sortOrder = 'desc';
        filterType = '';
        typeFilterBtns.forEach((b) => b.classList.remove('active'));
        document.querySelector('.filter-btn[data-type=""]').classList.add('active');
        applyFilters();
    });

    generateReportBtn.addEventListener('click', () => {
        selectedReportType = reportTypeSelect.value;
        selectedCompareMode = compareTypeSelect.value;
        renderReport();
    });

    const clearDbBtn = document.getElementById('clear-db-btn');
    if (clearDbBtn) {
        clearDbBtn.addEventListener('click', async () => {
            if (!confirm('Delete all data for your company? This cannot be undone.')) return;
            const res = await apiFetch('/api/transactions', { method: 'DELETE' });
            if (res.ok) {
                allTransactions = [];
                window.allTransactions = allTransactions;
                applyFilters();
                updateMetrics();
                renderSummary();
            }
        });
    }

    const modalCloseBtn = document.getElementById('modal-close-btn');
    const modalOverlay = document.getElementById('modal-overlay');
    if (modalCloseBtn) modalCloseBtn.addEventListener('click', hideTxnDetails);
    if (modalOverlay) modalOverlay.addEventListener('click', hideTxnDetails);
    window.addEventListener('keydown', (e) => { if (e.key === 'Escape') hideTxnDetails(); });

    // Webhook URL display
    const webhookUrlDisplay = document.getElementById('webhook-url-display');
    const copyWebhookBtn = document.getElementById('copy-webhook-btn');
    if (webhookUrlDisplay && currentCompany) {
        const baseUrl = `${window.location.protocol}//${window.location.host}`;
        webhookUrlDisplay.value = `${baseUrl}/api/webhook/sms/${currentCompany.company_code}`;
    }
    if (copyWebhookBtn) {
        copyWebhookBtn.addEventListener('click', () => {
            if (webhookUrlDisplay && webhookUrlDisplay.value) {
                navigator.clipboard.writeText(webhookUrlDisplay.value).then(() => {
                    const orig = copyWebhookBtn.textContent;
                    copyWebhookBtn.textContent = 'Copied!';
                    setTimeout(() => { copyWebhookBtn.textContent = orig; }, 1800);
                }).catch(() => {
                    webhookUrlDisplay.select();
                    document.execCommand('copy');
                });
            }
        });
    }
}

function initDashboard() {
    updateClock();

    if (!dashboardInitialized) {
        dashboardInitialized = true;
        setInterval(updateClock, 1000);
        bindDashboardEvents();
    }

    const webhookUrlDisplay = document.getElementById('webhook-url-display');
    if (webhookUrlDisplay && currentCompany) {
        const baseUrl = `${window.location.protocol}//${window.location.host}`;
        webhookUrlDisplay.value = `${baseUrl}/api/webhook/sms/${currentCompany.company_code}`;
    }

    fetchTransactions();
    connectWebSocket();
    startPollingFallback();
    renderReport();

    if (window.Notification && Notification.permission === 'default') {
        Notification.requestPermission();
    }

    window.dispatchEvent(new Event('cashin-dashboard-ready'));
}

bootstrapAuth();
