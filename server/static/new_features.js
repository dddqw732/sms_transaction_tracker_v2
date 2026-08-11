/* ═════════════════════════════════════════════════════════════════════════════ */
/* NEW FEATURES: Invoices, Notifications, Date Filtering, Excel Export           */
/* ═════════════════════════════════════════════════════════════════════════════ */

// ─── State ────────────────────────────────────────────────────────────────────
let allInvoices = [];
let notificationTimeouts = {};
const NOTIFICATION_DURATION = 180000; // 3 minutes

function secureFetch(url, options = {}) {
    if (typeof window.apiFetch === 'function') {
        return window.apiFetch(url, options);
    }
    return fetch(url, options);
}

// ─── DOM References ────────────────────────────────────────────────────────────
const invoiceModal = document.getElementById('invoice-modal');
const invoiceOverlay = document.getElementById('invoice-overlay');
const invoiceCloseBtn = document.getElementById('invoice-close');
const createInvoiceBtn = document.getElementById('create-invoice-btn');
const saveInvoiceBtn = document.getElementById('save-invoice-btn');
const invoicesList = document.getElementById('invoices-list');
const invoicesEmpty = document.getElementById('invoices-empty');
const invoiceSearch = document.getElementById('invoice-search');

const dateFilterModal = document.getElementById('date-filter-modal');
const dateFilterOverlay = document.getElementById('date-filter-overlay');
const dateFilterCloseBtn = document.getElementById('date-filter-close');
const applyFilterBtn = document.getElementById('apply-filter-btn');
const exportFilterBtn = document.getElementById('export-filter-btn');
const filterStartDate = document.getElementById('filter-start-date');
const filterEndDate = document.getElementById('filter-end-date');

const notificationPopup = document.getElementById('notification-popup');
const closeNotificationBtn = document.getElementById('close-notification');

// ─── Invoices Management ──────────────────────────────────────────────────────

function openInvoiceModal() {
    invoiceModal.classList.remove('hidden');
    document.getElementById('invoice-phone').value = '';
    document.getElementById('invoice-amount').value = '';
    document.getElementById('invoice-currency').value = 'SLSH';
    document.getElementById('invoice-description').value = '';
}

function closeInvoiceModal() {
    invoiceModal.classList.add('hidden');
}

function openDateFilterModal(filterType) {
    dateFilterModal.classList.remove('hidden');
    dateFilterModal.dataset.filterType = filterType;
    filterStartDate.value = '';
    filterEndDate.value = '';
}

function closeDateFilterModal() {
    dateFilterModal.classList.add('hidden');
}

async function loadInvoices() {
    try {
        const res = await secureFetch('/api/invoices');
        if (res.ok) {
            allInvoices = await res.json();
            renderInvoices();
        }
    } catch (err) {
        console.error('Failed to load invoices', err);
    }
}

async function createInvoice() {
    const phone = document.getElementById('invoice-phone').value.trim();
    const amount = parseFloat(document.getElementById('invoice-amount').value);
    const currency = document.getElementById('invoice-currency').value;
    const description = document.getElementById('invoice-description').value.trim();
    
    if (!phone || !amount || amount <= 0) {
        alert('Please fill in all required fields with valid values');
        return;
    }

    try {
        const res = await secureFetch('/api/invoices', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                invoice_number: phone,
                customer_phone: phone,
                amount: amount,
                currency: currency,
                description: description
            })
        });

        if (res.ok) {
            closeInvoiceModal();
            loadInvoices();
            showToast({ 
                type: 'Invoice Created', 
                sender: phone, 
                amount: amount, 
                timestamp: new Date().toISOString()
            });
        } else {
            alert('Failed to create invoice');
        }
    } catch (err) {
        console.error('Failed to create invoice', err);
    }
}

async function deleteInvoice(invoiceId) {
    if (confirm('Delete this invoice?')) {
        try {
            const res = await secureFetch(`/api/invoices/${invoiceId}`, { method: 'DELETE' });
            if (res.ok) {
                loadInvoices();
            }
        } catch (err) {
            console.error('Failed to delete invoice', err);
        }
    }
}

function renderInvoices(searchTerm = '') {
    const query = searchTerm.toLowerCase();
    const filtered = allInvoices.filter(inv =>
        inv.invoice_number.toLowerCase().includes(query) ||
        inv.customer_phone.toLowerCase().includes(query)
    );

    if (filtered.length === 0) {
        invoicesEmpty.classList.remove('hidden');
        invoicesList.innerHTML = '';
    } else {
        invoicesEmpty.classList.add('hidden');
        invoicesList.innerHTML = filtered.map(inv => {
            const statusClass = inv.status === 'paid' ? 'paid' : 'pending';
            const statusLabel = inv.status === 'paid' ? 'PAID' : 'PENDING';
            const createdDate = new Date(inv.created_at).toLocaleDateString();
            const paidDate = inv.paid_at ? new Date(inv.paid_at).toLocaleDateString() : '-';

            return `
                <div class="invoice-card">
                    <div class="invoice-header">
                        <div class="invoice-number">INV-${inv.invoice_number}</div>
                        <span class="invoice-status ${statusClass}">
                            <span style="width: 6px; height: 6px; border-radius: 50%; background: currentColor;"></span>
                            ${statusLabel}
                        </span>
                    </div>
                    <div class="invoice-detail">
                        <div class="invoice-detail-label">Customer</div>
                        <div class="invoice-detail-value">${inv.customer_phone}</div>
                    </div>
                    <div class="invoice-detail">
                        <div class="invoice-detail-label">Created</div>
                        <div class="invoice-detail-value">${createdDate}</div>
                    </div>
                    <div class="invoice-detail">
                        <div class="invoice-detail-label">Paid On</div>
                        <div class="invoice-detail-value">${paidDate}</div>
                    </div>
                    ${inv.description ? `
                    <div class="invoice-detail">
                        <div class="invoice-detail-label">Description</div>
                        <div class="invoice-detail-value">${inv.description}</div>
                    </div>
                    ` : ''}
                    <div class="invoice-amount">${formatAmount(inv.amount, inv.currency)}</div>
                    <div class="invoice-actions">
                        <button class="btn btn-secondary" onclick="deleteInvoice(${inv.id})">Delete</button>
                    </div>
                </div>
            `;
        }).join('');
    }
}

// ─── Notifications with Voice & Red Dot ────────────────────────────────────

function showNotificationPopup(txn) {
    const notifBody = document.getElementById('notification-body');
    const typeClass = txn.type === 'Received' ? 'received' : 'sent';
    const sign = txn.type === 'Received' ? '+' : '-';
    const displayAmount = formatAmount(txn.amount, txn.currency);
    const name = txn.type === 'Received' ? txn.sender : txn.receiver;
    const number = txn.type === 'Received' ? txn.sender_number : txn.receiver_number;

    notifBody.innerHTML = `
        <div style="color: var(--text-sub); font-size: 14px; line-height: 1.6;">
            <strong>${name || 'Unknown User'}</strong><br>
            <span class="${typeClass}" style="font-weight: 700;">${sign}${displayAmount}</span><br>
            <small>${txn.provider || 'Unknown Provider'}</small>
        </div>
    `;

    notificationPopup.classList.remove('hidden');
    
    // Play sound notification
    playNotificationSound();
    
    // Clear any existing timeout
    const txnId = txn.id;
    if (notificationTimeouts[txnId]) {
        clearTimeout(notificationTimeouts[txnId]);
    }
    
    // Auto-hide after 3 minutes
    notificationTimeouts[txnId] = setTimeout(() => {
        notificationPopup.classList.add('hidden');
        delete notificationTimeouts[txnId];
    }, NOTIFICATION_DURATION);
}

function playNotificationSound() {
    // Create a simple beep sound using Web Audio API
    try {
        const audioContext = new (window.AudioContext || window.webkitAudioContext)();
        const oscillator = audioContext.createOscillator();
        const gain = audioContext.createGain();
        
        oscillator.connect(gain);
        gain.connect(audioContext.destination);
        
        // Play a pleasant notification sound
        oscillator.frequency.setValueAtTime(800, audioContext.currentTime);
        oscillator.frequency.setValueAtTime(600, audioContext.currentTime + 0.1);
        
        gain.gain.setValueAtTime(0.3, audioContext.currentTime);
        gain.gain.setValueAtTime(0, audioContext.currentTime + 0.2);
        
        oscillator.start(audioContext.currentTime);
        oscillator.stop(audioContext.currentTime + 0.2);
    } catch (e) {
        console.log('Audio notification not available');
    }
}

// ─── Date Filtering ───────────────────────────────────────────────────────────

async function applyDateFilter() {
    const startDate = filterStartDate.value;
    const endDate = filterEndDate.value;
    const filterType = dateFilterModal.dataset.filterType;

    if (!startDate || !endDate) {
        alert('Please select both start and end dates');
        return;
    }

    const start = new Date(startDate).getTime();
    const end = new Date(endDate).getTime();

    if (start > end) {
        alert('Start date cannot be after end date');
        return;
    }

    // Filter transactions
    const filtered = allTransactions.filter(txn => {
        const txnTime = new Date(txn.timestamp).getTime();
        return txnTime >= start && txnTime <= end && 
               (filterType === 'balance' || txn.type === (filterType === 'received' ? 'Received' : 'Sent'));
    });

    // Display filtered results
    let total = 0;
    filtered.forEach(t => {
        if (filterType === 'balance') {
            total += t.type === 'Received' ? t.amount : -t.amount;
        } else {
            total += t.amount;
        }
    });

    alert(`${filterType.toUpperCase()}\n\nPeriod: ${startDate} to ${endDate}\n\nTotal: ${formatAmount(total, 'SLSH')}\n\nTransactions: ${filtered.length}`);
    
    closeDateFilterModal();
}

async function exportToExcel() {
    const startDate = filterStartDate.value;
    const endDate = filterEndDate.value;

    if (!startDate || !endDate) {
        alert('Please select both start and end dates');
        return;
    }

    try {
        const response = await secureFetch(`/api/export/excel?start_date=${startDate}&end_date=${endDate}`);
        if (response.ok) {
            const blob = await response.blob();
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = `transactions_${startDate}_to_${endDate}.xlsx`;
            document.body.appendChild(a);
            a.click();
            window.URL.revokeObjectURL(url);
            document.body.removeChild(a);
            closeDateFilterModal();
        } else {
            alert('Failed to export. Please try again.');
        }
    } catch (err) {
        console.error('Export failed', err);
        alert('Export failed: ' + err.message);
    }
}

// Export helper function
function showDateFilterModal(filterType) {
    openDateFilterModal(filterType);
}

// ─── Override handleNewTransaction to show notification ────────────────────

const originalHandleNewTransaction = window.handleNewTransaction;
window.handleNewTransaction = function(txn) {
    originalHandleNewTransaction.call(this, txn);
    if (txn.event !== 'cleared' && txn.type && txn.amount) {
        showNotificationPopup(txn);
        // Add animation to new transaction card
        setTimeout(() => {
            const firstCard = document.querySelector('.ledger-list .txn-card:first-child');
            if (firstCard) {
                firstCard.classList.add('new-transaction');
            }
        }, 100);
    }
};

// ─── Event Listeners ───────────────────────────────────────────────────────────

if (createInvoiceBtn) createInvoiceBtn.addEventListener('click', openInvoiceModal);
if (invoiceCloseBtn) invoiceCloseBtn.addEventListener('click', closeInvoiceModal);
if (invoiceOverlay) invoiceOverlay.addEventListener('click', closeInvoiceModal);
if (saveInvoiceBtn) saveInvoiceBtn.addEventListener('click', createInvoice);

if (dateFilterCloseBtn) dateFilterCloseBtn.addEventListener('click', closeDateFilterModal);
if (dateFilterOverlay) dateFilterOverlay.addEventListener('click', closeDateFilterModal);
if (applyFilterBtn) applyFilterBtn.addEventListener('click', applyDateFilter);
if (exportFilterBtn) exportFilterBtn.addEventListener('click', exportToExcel);

if (closeNotificationBtn) closeNotificationBtn.addEventListener('click', () => {
    notificationPopup.classList.add('hidden');
});

if (invoiceSearch) {
    invoiceSearch.addEventListener('input', (e) => {
        renderInvoices(e.target.value);
    });
}

// Close modals on Escape
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        if (!invoiceModal.classList.contains('hidden')) closeInvoiceModal();
        if (!dateFilterModal.classList.contains('hidden')) closeDateFilterModal();
    }
});

// ─── Initialization ────────────────────────────────────────────────────────────

// Load invoices when tab is opened
document.querySelectorAll('.nav-btn').forEach(btn => {
    if (btn.getAttribute('data-target') === 'tab-invoices') {
        btn.addEventListener('click', () => {
            setTimeout(loadInvoices, 100);
        });
    }
});

// Initial load after dashboard auth/bootstrap
window.addEventListener('cashin-dashboard-ready', () => {
    loadInvoices();
});

// Update delete button to require password
const originalClearDbBtn = document.getElementById('clear-db-btn');
if (originalClearDbBtn) {
    originalClearDbBtn.removeEventListener('click', null);
    originalClearDbBtn.addEventListener('click', async () => {
        if (confirm('Delete all data for your company? This cannot be undone.')) {
            try {
                const res = await secureFetch('/api/transactions', { 
                    method: 'DELETE'
                });
                if (res.ok) {
                    allTransactions = [];
                    applyFilters();
                    updateMetrics();
                    renderSummary();
                    alert('Company data cleared successfully');
                } else {
                    alert('Failed to clear company data');
                }
            } catch (err) {
                console.error('Failed to clear db', err);
            }
        }
    });
}
