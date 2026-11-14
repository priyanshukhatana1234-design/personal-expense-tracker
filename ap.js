(() => {
  'use strict';

  // ---------- state ----------
  const state = {
    month: currentMonth(),
    transactions: [],
    summary: null,
    categories: [],
    filter: 'ALL',
    query: '',
    editingId: null,
  };

  const PALETTE = ['#2f4bdb', '#e8590c', '#0f7a58', '#b4234f', '#7048e8', '#0c8599', '#c2a100', '#d6336c', '#495057', '#5c940d'];

  const $ = (id) => document.getElementById(id);
  const money0 = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });
  const money2 = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const money = (n) => (Number.isInteger(Number(n)) ? money0 : money2).format(Number(n));

  // ---------- helpers ----------
  function currentMonth() {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  }

  function shiftMonth(ym, delta) {
    const [y, m] = ym.split('-').map(Number);
    const d = new Date(y, m - 1 + delta, 1);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  }

  function monthLabel(ym) {
    const [y, m] = ym.split('-').map(Number);
    return new Date(y, m - 1, 1).toLocaleString('en-IN', { month: 'long', year: 'numeric' });
  }

  function todayInMonth(ym) {
    const today = new Date();
    const iso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
    return iso.startsWith(ym) ? iso : `${ym}-01`;
  }

  function colorFor(category) {
    const i = state.categories.indexOf(category);
    if (i >= 0) return PALETTE[i % PALETTE.length];
    let h = 0;
    for (const ch of category) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
    return PALETTE[h % PALETTE.length];
  }

  /** Builds an element; children are text or nodes, never parsed as HTML. */
  function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(props)) {
      if (k === 'class') node.className = v;
      else if (k === 'style') Object.assign(node.style, v);
      else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
      else node.setAttribute(k, v);
    }
    for (const c of children) node.append(c);
    return node;
  }

  async function api(path, options = {}) {
    const init = { method: options.method || 'GET', headers: {} };
    if (options.body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(options.body);
    }
    let res;
    try {
      res = await fetch(path, init);
    } catch {
      throw new Error('Cannot reach the server. Is the app still running?');
    }
    if (res.status === 204) return null;
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
    return data;
  }

  let toastTimer;
  function toast(message) {
    const t = $('toast');
    t.textContent = message;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 2200);
  }

  // ---------- data loading ----------
  async function refresh() {
    try {
      const [txs, summary] = await Promise.all([
        api(`/api/transactions?month=${state.month}`),
        api(`/api/summary?month=${state.month}`),
      ]);
      state.transactions = txs;
      state.summary = summary;
      render();
    } catch (e) {
      toast(e.message);
    }
  }

  // ---------- rendering ----------
  function render() {
    $('month').value = state.month;
    $('monthName').textContent = monthLabel(state.month);
    $('exportLink').href = `/api/export?month=${state.month}`;
    renderOverview();
    renderRows();
  }

  function renderOverview() {
    const s = state.summary;
    const expense = Number(s.expense);
    const budget = Number(s.budget);
    const balance = Number(s.balance);

    $('spent').textContent = money(expense);
    $('income').textContent = money(s.income);
    const bal = $('balance');
    bal.textContent = money(balance);
    bal.className = balance < 0 ? 'neg' : '';

    // budget sentence
    const status = $('budgetStatus');
    status.replaceChildren();
    if (budget > 0) {
      const diff = budget - expense;
      if (diff >= 0) status.append(`${money(diff)} left of your ${money(budget)} budget.`);
      else status.append(el('span', { class: 'over' }, `${money(-diff)} over`), ` your ${money(budget)} budget.`);
      $('budgetBtn').textContent = 'Change budget';
    } else {
      status.append('No budget set.');
      $('budgetBtn').textContent = 'Set budget';
    }

    // stacked category bar, scaled against whichever is larger: budget or spending
    const bar = $('bar');
    const legend = $('legend');
    bar.replaceChildren();
    legend.replaceChildren();
    const entries = Object.entries(s.expenseByCategory);
    const scale = Math.max(budget, expense);

    if (!entries.length) {
      bar.setAttribute('aria-label', 'No spending recorded this month');
      legend.append(el('li', {}, el('span', { class: 'name' }, 'No spending recorded this month.')));
      return;
    }

    const parts = [];
    for (const [cat, amt] of entries) {
      const share = Number(amt);
      parts.push(`${cat} ${money(share)}`);
      bar.append(el('div', {
        class: 'seg-fill',
        title: `${cat}: ${money(share)}`,
        style: { width: `${(share / scale) * 100}%`, background: colorFor(cat) },
      }));
      legend.append(el('li', {},
        el('span', { class: 'dot', style: { background: colorFor(cat) } }),
        el('span', { class: 'name' }, cat),
        el('span', { class: 'amt' }, money(share)),
        el('span', { class: 'pct' }, `${Math.round((share / expense) * 100)}%`)));
    }
    if (budget > 0 && expense > budget) {
      bar.append(el('div', { class: 'marker', title: `Budget ${money(budget)}`, style: { left: `${(budget / scale) * 100}%` } }));
    }
    bar.setAttribute('aria-label', `Spending by category: ${parts.join(', ')}`);
  }

  function visibleTransactions() {
    const q = state.query.trim().toLowerCase();
    return state.transactions.filter((t) => {
      if (state.filter !== 'ALL' && t.type !== state.filter) return false;
      if (!q) return true;
      return [t.title, t.category, t.note].some((v) => (v || '').toLowerCase().includes(q));
    });
  }

  function renderRows() {
    const rows = $('rows');
    rows.replaceChildren();
    const list = visibleTransactions();
    $('empty').hidden = list.length > 0;

    if (!list.length) {
      $('emptyText').textContent = state.transactions.length
        ? 'Nothing matches your filter. Clear the search or switch to All.'
        : `No transactions in ${monthLabel(state.month)} yet. Add your first one to start the chart.`;
      return;
    }

    for (const t of list) {
      const isIncome = t.type === 'INCOME';
      const [, mm, dd] = t.date.split('-');
      const dateText = new Date(Number(t.date.slice(0, 4)), Number(mm) - 1, Number(dd))
        .toLocaleString('en-IN', { day: 'numeric', month: 'short' });
      const meta = el('div', { class: 'meta' },
        el('span', { class: 'dot', style: { background: colorFor(t.category) } }),
        el('span', {}, t.category));
      if (t.note) meta.append(el('span', {}, `\u2013 ${t.note}`));

      rows.append(el('li', {},
        el('span', { class: 'date' }, dateText),
        el('div', {}, el('div', { class: 'title' }, t.title), meta),
        el('span', { class: `amount ${isIncome ? 'in' : ''}` }, `${isIncome ? '+' : '\u2212'}${money(t.amount)}`),
        el('div', { class: 'row-actions' },
          el('button', { type: 'button', 'aria-label': `Edit ${t.title}`, onclick: () => openTxDialog(t) }, 'Edit'),
          el('button', { type: 'button', class: 'del', 'aria-label': `Delete ${t.title}`, onclick: () => removeTx(t) }, 'Delete'))));
    }
  }

  // ---------- transaction dialog ----------
  function openTxDialog(tx) {
    const form = $('txForm');
    form.reset();
    $('formError').textContent = '';
    state.editingId = tx ? tx.id : null;
    $('txTitle').textContent = tx ? 'Edit transaction' : 'Add transaction';
    $('saveTx').textContent = tx ? 'Save changes' : 'Save transaction';

    form.elements.type.value = tx ? tx.type : 'EXPENSE';
    form.elements.title.value = tx ? tx.title : '';
    form.elements.amount.value = tx ? tx.amount : '';
    form.elements.date.value = tx ? tx.date : todayInMonth(state.month);
    form.elements.category.value = tx ? tx.category : '';
    form.elements.note.value = tx ? tx.note || '' : '';
    $('txDialog').showModal();
    form.elements.title.focus();
  }

  async function submitTx(e) {
    e.preventDefault();
    const form = $('txForm');
    const f = form.elements;
    const body = {
      type: f.type.value,
      title: f.title.value,
      amount: f.amount.value,
      date: f.date.value,
      category: f.category.value,
      note: f.note.value,
    };
    const save = $('saveTx');
    save.disabled = true;
    try {
      if (state.editingId) {
        await api(`/api/transactions/${state.editingId}`, { method: 'PUT', body });
        toast('Changes saved');
      } else {
        await api('/api/transactions', { method: 'POST', body });
        toast('Transaction saved');
      }
      $('txDialog').close();
      // jump to the month of the saved entry so it is visible
      state.month = body.date.slice(0, 7);
      await loadCategories();
      await refresh();
    } catch (err) {
      $('formError').textContent = err.message;
    } finally {
      save.disabled = false;
    }
  }

  async function removeTx(t) {
    if (!confirm(`Delete "${t.title}" (${money(t.amount)})? This cannot be undone.`)) return;
    try {
      await api(`/api/transactions/${t.id}`, { method: 'DELETE' });
      toast('Transaction deleted');
      await refresh();
    } catch (e) {
      toast(e.message);
    }
  }

  // ---------- budget dialog ----------
  function openBudgetDialog() {
    $('budgetError').textContent = '';
    $('budgetForm').elements.budget.value = Number(state.summary.budget) || '';
    $('budgetDialog').showModal();
    $('budgetForm').elements.budget.focus();
  }

  async function submitBudget(e) {
    e.preventDefault();
    try {
      await api('/api/budget', { method: 'PUT', body: { budget: $('budgetForm').elements.budget.value || '0' } });
      $('budgetDialog').close();
      toast('Budget saved');
      await refresh();
    } catch (err) {
      $('budgetError').textContent = err.message;
    }
  }

  // ---------- categories ----------
  async function loadCategories() {
    try {
      const defaults = await api('/api/categories');
      const all = await api('/api/transactions');
      const extra = [...new Set(all.map((t) => t.category))].filter((c) => !defaults.includes(c));
      state.categories = [...defaults, ...extra];
      $('categoryList').replaceChildren(...state.categories.map((c) => el('option', { value: c })));
    } catch (e) {
      toast(e.message);
    }
  }

  // ---------- wiring ----------
  function init() {
    $('prevMonth').addEventListener('click', () => { state.month = shiftMonth(state.month, -1); refresh(); });
    $('nextMonth').addEventListener('click', () => { state.month = shiftMonth(state.month, 1); refresh(); });
    $('month').addEventListener('change', (e) => {
      if (/^\d{4}-\d{2}$/.test(e.target.value)) { state.month = e.target.value; refresh(); }
    });

    $('addBtn').addEventListener('click', () => openTxDialog(null));
    $('emptyAdd').addEventListener('click', () => openTxDialog(null));
    $('cancelTx').addEventListener('click', () => $('txDialog').close());
    $('txForm').addEventListener('submit', submitTx);

    $('budgetBtn').addEventListener('click', openBudgetDialog);
    $('cancelBudget').addEventListener('click', () => $('budgetDialog').close());
    $('budgetForm').addEventListener('submit', submitBudget);

    $('search').addEventListener('input', (e) => { state.query = e.target.value; renderRows(); });
    document.querySelectorAll('[data-filter]').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.filter = btn.dataset.filter;
        document.querySelectorAll('[data-filter]').forEach((b) => b.setAttribute('aria-pressed', String(b === btn)));
        renderRows();
      });
    });

    loadCategories().then(refresh);
  }

  init();
})();
