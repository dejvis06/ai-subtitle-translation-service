/* ─── Configuration ─────────────────────────────────────────────────────────── */
const API_BASE = 'http://localhost:8080';

/* ─── Available languages ───────────────────────────────────────────────────── */
const LANGUAGES = [
  'Albanian',
  // More languages can be added here as the service expands
];

/* ─── State ─────────────────────────────────────────────────────────────────── */
let selectedFile    = null;
let selectedLang    = '';
let currentJobId    = null;
let currentEventSrc = null;
let blobDownloadUrl = null;   // kept alive so the manual button can re-use it
let blobFilename    = null;

/* ─── DOM refs ──────────────────────────────────────────────────────────────── */
const dropZone          = document.getElementById('drop-zone');
const fileInput         = document.getElementById('file-input');
const fileBadge         = document.getElementById('file-badge');
const fileNameDisplay   = document.getElementById('file-name-display');
const removeFileBtn     = document.getElementById('remove-file');

const langInput         = document.getElementById('language-input');
const langListbox       = document.getElementById('language-listbox');

const translateBtn      = document.getElementById('translate-btn');

const uploadCard        = document.getElementById('upload-card');
const progressCard      = document.getElementById('progress-card');
const progressHeader    = document.getElementById('progress-header');
const progressStatusTxt = document.getElementById('progress-status-text');
const progressBarCont   = document.getElementById('progress-bar-container');
const progressFill      = document.getElementById('progress-fill');
const progressPct       = document.getElementById('progress-pct');
const progressBatchInfo = document.getElementById('progress-batch-info');

const doneState         = document.getElementById('done-state');
const downloadBtn       = document.getElementById('download-btn');
const newTranslationBtn = document.getElementById('new-translation-btn');

const errorState        = document.getElementById('error-state');
const errorText         = document.getElementById('error-text');
const retryBtn          = document.getElementById('retry-btn');

/* ═══════════════════════════════════════════════════════════════════════════════
   FILE HANDLING
═══════════════════════════════════════════════════════════════════════════════ */

const DROP_PROMPT_SELECTORS = ['.drop-icon', '.drop-main', '.drop-sub', '.btn-outline'];

function setFile(file) {
  if (!file || !file.name.toLowerCase().endsWith('.srt')) {
    alert('Please select a valid .srt subtitle file.');
    return;
  }
  selectedFile = file;
  fileNameDisplay.textContent = file.name;

  DROP_PROMPT_SELECTORS.forEach(sel => {
    dropZone.querySelector(sel).style.display = 'none';
  });
  fileBadge.hidden = false;
  dropZone.classList.add('has-file');
  refreshTranslateBtn();
}

function clearFile() {
  selectedFile = null;
  fileBadge.hidden = true;
  fileInput.value = '';
  DROP_PROMPT_SELECTORS.forEach(sel => {
    dropZone.querySelector(sel).style.display = '';
  });
  dropZone.classList.remove('has-file');
  refreshTranslateBtn();
}

// Drag & drop
dropZone.addEventListener('dragover', e => {
  e.preventDefault();
  dropZone.classList.add('drag-over');
});

['dragleave', 'dragend'].forEach(evt =>
  dropZone.addEventListener(evt, () => dropZone.classList.remove('drag-over'))
);

dropZone.addEventListener('drop', e => {
  e.preventDefault();
  dropZone.classList.remove('drag-over');
  const file = e.dataTransfer?.files?.[0];
  if (file) setFile(file);
});

// Click to open file picker (but not when clicking the badge's remove button)
dropZone.addEventListener('click', e => {
  if (e.target === removeFileBtn || removeFileBtn.contains(e.target)) return;
  if (!selectedFile) fileInput.click();
});

dropZone.addEventListener('keydown', e => {
  if ((e.key === 'Enter' || e.key === ' ') && !selectedFile) {
    e.preventDefault();
    fileInput.click();
  }
});

fileInput.addEventListener('change', () => {
  if (fileInput.files[0]) setFile(fileInput.files[0]);
});

removeFileBtn.addEventListener('click', e => {
  e.stopPropagation();
  clearFile();
});

/* ═══════════════════════════════════════════════════════════════════════════════
   LANGUAGE SELECTOR  (searchable dropdown)
═══════════════════════════════════════════════════════════════════════════════ */

function renderDropdown(filter) {
  const q = filter.toLowerCase().trim();
  const matches = LANGUAGES.filter(l => l.toLowerCase().includes(q));

  langListbox.innerHTML = '';

  if (matches.length === 0) {
    const li = document.createElement('li');
    li.className = 'no-results';
    li.textContent = 'No languages found';
    langListbox.appendChild(li);
  } else {
    matches.forEach(lang => {
      const li = document.createElement('li');
      li.textContent = lang;
      li.setAttribute('role', 'option');
      li.setAttribute('aria-selected', lang === selectedLang ? 'true' : 'false');
      li.addEventListener('click', () => selectLanguage(lang));
      langListbox.appendChild(li);
    });
  }
}

function openDropdown() {
  renderDropdown(langInput.value);
  langListbox.hidden = false;
  langInput.setAttribute('aria-expanded', 'true');
}

function closeDropdown() {
  langListbox.hidden = true;
  langInput.setAttribute('aria-expanded', 'false');
}

function selectLanguage(lang) {
  selectedLang = lang;
  langInput.value = lang;
  closeDropdown();
  refreshTranslateBtn();
}

langInput.addEventListener('focus', openDropdown);

langInput.addEventListener('input', () => {
  selectedLang = '';
  renderDropdown(langInput.value);
  langListbox.hidden = false;
  refreshTranslateBtn();
});

langInput.addEventListener('keydown', e => {
  if (e.key === 'Escape') closeDropdown();
  if (e.key === 'Enter') {
    const first = langListbox.querySelector('li:not(.no-results)');
    if (first) { first.click(); }
  }
});

document.addEventListener('click', e => {
  if (!langInput.contains(e.target) && !langListbox.contains(e.target)) {
    closeDropdown();
    // If user typed something that doesn't match, clear it
    if (!LANGUAGES.includes(langInput.value)) {
      langInput.value = selectedLang;
    }
  }
});

/* ═══════════════════════════════════════════════════════════════════════════════
   TRANSLATE BUTTON
═══════════════════════════════════════════════════════════════════════════════ */

function refreshTranslateBtn() {
  translateBtn.disabled = !(selectedFile && selectedLang);
}

translateBtn.addEventListener('click', startTranslation);

/* ═══════════════════════════════════════════════════════════════════════════════
   TRANSLATION FLOW
═══════════════════════════════════════════════════════════════════════════════ */

async function startTranslation() {
  if (!selectedFile || !selectedLang) return;

  // Switch to progress view
  uploadCard.hidden = true;
  progressCard.hidden = false;
  resetProgressUI();

  try {
    // 1. Submit the job
    const formData = new FormData();
    formData.append('file', selectedFile);
    formData.append('translateTo', selectedLang);

    const res = await fetch(`${API_BASE}/api/translations`, {
      method: 'POST',
      body: formData,
    });

    if (!res.ok) {
      throw new Error(`Server returned ${res.status}: ${res.statusText}`);
    }

    const { jobId } = await res.json();
    currentJobId = jobId;

    // 2. Subscribe to SSE progress stream
    connectSSE(jobId);

  } catch (err) {
    showError(err.message || 'Failed to start translation.');
  }
}

function connectSSE(jobId) {
  if (currentEventSrc) {
    currentEventSrc.close();
    currentEventSrc = null;
  }

  const url = `${API_BASE}/api/translations/${jobId}/events`;
  const es = new EventSource(url);
  currentEventSrc = es;

  es.addEventListener('progress', e => {
    const data = JSON.parse(e.data);
    updateProgress(data.percentage, data.processedBatches, data.totalBatches);
  });

  es.addEventListener('done', async () => {
    es.close();
    currentEventSrc = null;
    updateProgress(100, null, null);
    await triggerDownload(jobId);
    showDone();
  });

  es.addEventListener('error', e => {
    es.close();
    currentEventSrc = null;
    let message = 'An error occurred during translation.';
    try { message = JSON.parse(e.data).message || message; } catch (_) {}
    showError(message);
  });

  es.onerror = () => {
    // Only show error if we haven't completed yet
    if (!doneState.hidden) return;
    es.close();
    currentEventSrc = null;
    showError('Lost connection to the server. The translation may have failed.');
  };
}

function updateProgress(pct, processed, total) {
  progressFill.style.width = `${pct}%`;
  progressPct.textContent = `${pct}%`;
  progressBarCont.setAttribute('aria-valuenow', pct);

  if (processed !== null && total !== null) {
    progressBatchInfo.textContent = `Batch ${processed} of ${total}`;
    progressStatusTxt.textContent = pct < 100
      ? `Translating… ${pct}%`
      : 'Finalizing…';
  } else {
    progressBatchInfo.textContent = '';
    progressStatusTxt.textContent = 'Finalizing…';
  }
}

async function triggerDownload(jobId) {
  try {
    const res = await fetch(`${API_BASE}/api/translations/${jobId}/download`);
    if (!res.ok) throw new Error('Download failed');

    const disposition = res.headers.get('Content-Disposition') || '';
    const match = disposition.match(/filename="([^"]+)"/);
    blobFilename    = match ? match[1] : 'translated.srt';
    blobDownloadUrl = URL.createObjectURL(await res.blob());

    // Wire the anchor so clicking it is a plain native download — no JS needed
    downloadBtn.href     = blobDownloadUrl;
    downloadBtn.download = blobFilename;

    // Auto-trigger once
    downloadBtn.click();

  } catch (err) {
    console.warn('Auto-download failed:', err.message);
  }
}

/* ═══════════════════════════════════════════════════════════════════════════════
   UI STATE HELPERS
═══════════════════════════════════════════════════════════════════════════════ */

function resetProgressUI() {
  progressFill.style.width = '0%';
  progressPct.textContent  = '0%';
  progressBatchInfo.textContent = '';
  progressStatusTxt.textContent = 'Starting translation…';
  progressBarCont.setAttribute('aria-valuenow', 0);

  doneState.hidden  = true;
  errorState.hidden = true;

  // Re-show spinner
  progressHeader.querySelector('.spinner').hidden = false;
  progressStatusTxt.hidden = false;
}

function showDone() {
  progressHeader.querySelector('.spinner').hidden = true;
  progressStatusTxt.hidden = true;
  progressBarCont.parentElement; // track stays visible with full bar

  doneState.hidden  = false;
  errorState.hidden = true;
}

function showError(message) {
  progressCard.hidden = false;
  uploadCard.hidden   = true;

  progressHeader.querySelector('.spinner').hidden = true;
  progressStatusTxt.hidden = true;
  progressBarCont.setAttribute('aria-valuenow', 0);

  errorText.textContent = message;
  errorState.hidden  = false;
  doneState.hidden   = true;
}

function resetToUpload() {
  if (currentEventSrc) { currentEventSrc.close(); currentEventSrc = null; }
  currentJobId = null;
  if (blobDownloadUrl) { URL.revokeObjectURL(blobDownloadUrl); blobDownloadUrl = null; }
  blobFilename = null;

  uploadCard.hidden   = false;
  progressCard.hidden = true;

  clearFile();
  selectedLang = '';
  langInput.value = '';
  refreshTranslateBtn();
}

newTranslationBtn.addEventListener('click', resetToUpload);
retryBtn.addEventListener('click', resetToUpload);

/* ─── Boot ──────────────────────────────────────────────────────────────────── */
// Pre-populate the dropdown with available languages on first focus
langInput.addEventListener('focus', openDropdown, { once: false });
refreshTranslateBtn();
