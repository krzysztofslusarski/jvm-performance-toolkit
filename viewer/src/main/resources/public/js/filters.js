// Helpers of the event filters (templates/fragments/filters.html), shared by the viewer sidebar and the upload page

function toggleFilter(contentId, checkbox) {
    var el = document.getElementById(contentId);
    if (el) el.classList.toggle('visible', checkbox.checked);
}

var timeFilterIds = ['endDurationContent', 'startEndTimestampContent', 'warmupCooldownContent', 'warmupDurationContent'];
var timeCheckboxIds = ['endDurationOn', 'startEndTimestampOn', 'warmupCooldownOn', 'warmupDurationOn'];

function toggleTimeFilter(contentId, checkbox) {
    // Time filters are mutually exclusive
    timeFilterIds.forEach(function (id) {
        var el = document.getElementById(id);
        if (el) el.classList.remove('visible');
    });
    timeCheckboxIds.forEach(function (id) {
        var cb = document.getElementById(id);
        if (cb && cb !== checkbox) cb.checked = false;
    });
    if (checkbox.checked) {
        var el = document.getElementById(contentId);
        if (el) el.classList.add('visible');
    }
}

// ---- Multi-filter rows ----
function addFilterRow(listId, prefix, placeholder) {
    var list = document.getElementById(listId);
    var rows = list.querySelectorAll('.multi-filter-row');
    var index = rows.length;
    var div = document.createElement('div');
    div.className = 'filter-row multi-filter-row';
    div.innerHTML = '<input name="' + prefix + '_' + index + '" type="text" placeholder="' + (placeholder || 'e.g. com.example') + '"/>' +
        '<button type="button" class="btn-remove-filter" onclick="removeFilterRow(this, \'' + listId + '\')">' +
        '<svg width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>';
    list.appendChild(div);
    updateRemoveButtons(listId);
}

function removeFilterRow(btn, listId) {
    btn.closest('.multi-filter-row').remove();
    reindexFilterRows(listId);
    updateRemoveButtons(listId);
}

function reindexFilterRows(listId) {
    var prefix = listId.replace(/List$/, '');
    var rows = document.querySelectorAll('#' + listId + ' .multi-filter-row');
    rows.forEach(function (row, index) {
        var input = row.querySelector('input[type=text]');
        if (input) input.name = prefix + '_' + index;
    });
}

function updateRemoveButtons(listId) {
    var rows = document.querySelectorAll('#' + listId + ' .multi-filter-row');
    rows.forEach(function (row) {
        var btn = row.querySelector('.btn-remove-filter');
        if (btn) btn.style.display = rows.length <= 1 ? 'none' : '';
    });
}
