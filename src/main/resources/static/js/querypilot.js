(function () {
    'use strict';

    const ANALYSIS_ENDPOINT = '/api/v1/query-analysis';
    const SANDBOX_HEALTH_ENDPOINT = '/api/v1/sandbox/health';

    const sqlInput = document.getElementById('sql-input');
    const analyzeBtn = document.getElementById('analyze-btn');
    const analyzeSpinner = document.getElementById('analyze-spinner');
    const analyzeLabel = document.getElementById('analyze-btn-label');
    const clearBtn = document.getElementById('clear-btn');

    const loadingBanner = document.getElementById('loading-banner');
    const errorBanner = document.getElementById('error-banner');
    const errorTitle = document.getElementById('error-title');
    const errorMessage = document.getElementById('error-message');
    const results = document.getElementById('results');

    const metricsGrid = document.getElementById('metrics-grid');
    const planSummaryGrid = document.getElementById('plan-summary-grid');
    const issuesList = document.getElementById('issues-list');
    const joinsPanel = document.getElementById('joins-panel');
    const joinsList = document.getElementById('joins-list');
    const operationsPanel = document.getElementById('operations-panel');
    const operationsList = document.getElementById('operations-list');
    const candidatesPanel = document.getElementById('candidates-panel');
    const candidatesList = document.getElementById('candidates-list');
    const validationsPanel = document.getElementById('validations-panel');
    const validationsList = document.getElementById('validations-list');
    const aiContent = document.getElementById('ai-content');
    const aiValidationsPanel = document.getElementById('ai-validations-panel');
    const aiValidationsList = document.getElementById('ai-validations-list');

    const sandboxStatus = document.getElementById('sandbox-status');
    const statusText = document.getElementById('status-text');

    // -----------------------------------------------------------
    // Formatting helpers
    // -----------------------------------------------------------

    function formatMs(value) {
        if (typeof value !== 'number' || Number.isNaN(value)) {
            return '—';
        }
        return value.toFixed(2) + ' ms';
    }

    function formatCost(value) {
        if (typeof value !== 'number' || Number.isNaN(value)) {
            return '—';
        }
        return value.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }

    function formatNumber(value) {
        if (typeof value !== 'number' || Number.isNaN(value)) {
            return '—';
        }
        return value.toLocaleString('en-US');
    }

    function formatPercent(value) {
        if (typeof value !== 'number' || Number.isNaN(value)) {
            return '—';
        }
        return value.toFixed(2) + '%';
    }

    // -----------------------------------------------------------
    // DOM helpers
    // -----------------------------------------------------------

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text !== undefined && text !== null) {
            node.textContent = text;
        }
        return node;
    }

    function clear(node) {
        while (node.firstChild) {
            node.removeChild(node.firstChild);
        }
    }

    function severityBadgeClass(severity) {
        switch ((severity || '').toUpperCase()) {
            case 'HIGH':
                return 'badge badge-high';
            case 'MEDIUM':
                return 'badge badge-medium';
            case 'LOW':
                return 'badge badge-low';
            default:
                return 'badge badge-medium';
        }
    }

    function detailItem(label, value) {
        const wrapper = el('div');
        wrapper.appendChild(el('div', 'detail-label', label));
        wrapper.appendChild(el('div', 'detail-value', value));
        return wrapper;
    }

    // -----------------------------------------------------------
    // Sandbox status
    // -----------------------------------------------------------

    function checkSandboxStatus() {
        fetch(SANDBOX_HEALTH_ENDPOINT)
            .then((response) => {
                if (!response.ok) {
                    throw new Error('sandbox health check failed');
                }
                return response.json();
            })
            .then((connected) => {
                if (connected === true) {
                    sandboxStatus.className = 'sandbox-status connected';
                    statusText.textContent = 'Sandbox Connected';
                } else {
                    sandboxStatus.className = 'sandbox-status unavailable';
                    statusText.textContent = 'Sandbox Unavailable';
                }
            })
            .catch(() => {
                sandboxStatus.className = 'sandbox-status unavailable';
                statusText.textContent = 'Sandbox Unavailable';
            });
    }

    // -----------------------------------------------------------
    // Rendering: metrics + plan summary
    // -----------------------------------------------------------

    function renderMetrics(data) {
        clear(metricsGrid);
        const metrics = [
            ['Execution Time', formatMs(data.executionTimeMs)],
            ['Planning Time', formatMs(data.planningTimeMs)],
            ['Total Cost', formatCost(data.plan ? data.plan.totalCost : undefined)],
            ['Actual Rows', formatNumber(data.plan ? data.plan.actualRows : undefined)]
        ];
        metrics.forEach(([label, value]) => {
            const card = el('div', 'metric-card');
            card.appendChild(el('div', 'metric-label', label));
            card.appendChild(el('div', 'metric-value', value));
            metricsGrid.appendChild(card);
        });
    }

    function renderPlanSummary(plan) {
        clear(planSummaryGrid);
        if (!plan) {
            return;
        }
        const stats = [
            ['Root Node', plan.rootNodeType],
            ['Estimated Rows', formatNumber(plan.estimatedRows)],
            ['Actual Rows', formatNumber(plan.actualRows)],
            ['Total Cost', formatCost(plan.totalCost)]
        ];
        stats.forEach(([label, value]) => {
            const wrapper = el('div');
            wrapper.appendChild(el('div', 'plan-stat-label', label));
            wrapper.appendChild(el('div', 'plan-stat-value', value));
            planSummaryGrid.appendChild(wrapper);
        });
    }

    // -----------------------------------------------------------
    // Rendering: issues
    // -----------------------------------------------------------

    function renderIssues(issues) {
        clear(issuesList);
        if (!issues || issues.length === 0) {
            issuesList.appendChild(el('div', 'empty-state', 'No significant plan issues detected.'));
            return;
        }

        issues.forEach((issue) => {
            const card = el('div', 'card');

            const header = el('div', 'card-header');
            header.appendChild(el('span', severityBadgeClass(issue.severity), issue.severity));
            header.appendChild(el('span', 'card-title', formatIssueType(issue.type)));
            card.appendChild(header);

            const grid = el('div', 'detail-grid');
            grid.appendChild(detailItem('Table', issue.relation || '—'));
            grid.appendChild(detailItem('Filter', issue.filter || '—'));
            grid.appendChild(detailItem('Rows Removed', formatNumber(issue.rowsRemovedByFilter)));
            grid.appendChild(detailItem('Actual Rows', formatNumber(issue.actualRows)));
            grid.appendChild(detailItem('Total Rows Processed', formatNumber(issue.totalActualRowsProcessed)));
            card.appendChild(grid);

            issuesList.appendChild(card);
        });
    }

    function formatIssueType(type) {
        if (!type) {
            return 'Issue';
        }
        return type
            .split('_')
            .map((word) => word.charAt(0) + word.slice(1).toLowerCase())
            .join(' ');
    }

    // -----------------------------------------------------------
    // Rendering: joins
    // -----------------------------------------------------------

    function renderJoins(joins) {
        clear(joinsList);
        if (!joins || joins.length === 0) {
            joinsPanel.hidden = true;
            return;
        }
        joinsPanel.hidden = false;

        joins.forEach((join) => {
            const card = el('div', 'card');

            const header = el('div', 'card-header');
            header.appendChild(el('span', 'card-title', join.type || 'Join'));
            card.appendChild(header);

            const relationsLabel = (join.relations && join.relations.length > 0)
                ? join.relations.join(' ↔ ')
                : '—';
            card.appendChild(el('div', 'reason-text', relationsLabel));

            const grid = el('div', 'detail-grid');
            grid.appendChild(detailItem('Estimated Rows', formatNumber(join.estimatedRows)));
            grid.appendChild(detailItem('Actual Rows', formatNumber(join.actualRows)));
            grid.appendChild(detailItem('Loops', formatNumber(join.actualLoops)));
            grid.appendChild(detailItem('Cost', formatCost(join.totalCost)));
            card.appendChild(grid);

            joinsList.appendChild(card);
        });
    }

    // -----------------------------------------------------------
    // Rendering: operations
    // -----------------------------------------------------------

    function renderOperations(operations) {
        clear(operationsList);
        if (!operations || operations.length === 0) {
            operationsPanel.hidden = true;
            return;
        }
        operationsPanel.hidden = false;

        operations.forEach((operation) => {
            const card = el('div', 'card');

            const header = el('div', 'card-header');
            header.appendChild(el('span', 'card-title', (operation.type || 'Operation').toUpperCase()));
            header.appendChild(el('span', severityBadgeClass(operation.severity), operation.severity));
            card.appendChild(header);

            const grid = el('div', 'detail-grid');
            if (operation.relation) {
                grid.appendChild(detailItem('Table', operation.relation));
            }
            grid.appendChild(detailItem('Estimated Rows', formatNumber(operation.estimatedRows)));
            grid.appendChild(detailItem('Actual Rows', formatNumber(operation.actualRows)));
            grid.appendChild(detailItem('Loops', formatNumber(operation.actualLoops)));
            grid.appendChild(detailItem('Cost', formatCost(operation.totalCost)));
            card.appendChild(grid);

            operationsList.appendChild(card);
        });
    }

    // -----------------------------------------------------------
    // Rendering: optimization candidates
    // -----------------------------------------------------------

    function renderCandidates(candidates) {
        clear(candidatesList);
        if (!candidates || candidates.length === 0) {
            candidatesPanel.hidden = true;
            return;
        }
        candidatesPanel.hidden = false;

        candidates.forEach((candidate) => {
            const card = el('div', 'card');

            const header = el('div', 'card-header');
            header.appendChild(el('span', 'badge badge-low', candidate.type));
            const columns = (candidate.columns && candidate.columns.length > 0)
                ? candidate.columns.join(', ')
                : '';
            header.appendChild(el('span', 'card-title', `${candidate.table || ''}(${columns})`));
            card.appendChild(header);

            if (candidate.proposedSql) {
                card.appendChild(el('div', 'detail-label', 'Proposed SQL'));
                card.appendChild(el('code', 'code-block', candidate.proposedSql));
            }

            if (candidate.reason) {
                card.appendChild(el('div', 'detail-label', 'Reason'));
                card.appendChild(el('p', 'reason-text', candidate.reason));
            }

            const footer = el('div', 'candidate-footer');
            const copyBtn = el('button', 'btn btn-ghost btn-small', 'Copy SQL');
            copyBtn.type = 'button';
            const copiedTag = el('span', 'copied-tag', 'Copied');
            copiedTag.hidden = true;

            copyBtn.addEventListener('click', () => {
                if (!candidate.proposedSql) {
                    return;
                }
                navigator.clipboard.writeText(candidate.proposedSql).then(() => {
                    copiedTag.hidden = false;
                    setTimeout(() => {
                        copiedTag.hidden = true;
                    }, 1600);
                });
            });

            footer.appendChild(copyBtn);
            footer.appendChild(copiedTag);
            card.appendChild(footer);

            candidatesList.appendChild(card);
        });
    }

    // -----------------------------------------------------------
    // Rendering: validations (deterministic + AI)
    // -----------------------------------------------------------

    function renderValidationCard(validation) {
        const card = el('div', 'card validation-card');

        const header = el('div', 'validation-header');
        const candidate = validation.candidate;
        const columns = (candidate && candidate.columns && candidate.columns.length > 0)
            ? candidate.columns.join(', ')
            : '';
        const label = candidate ? `${candidate.type} — ${candidate.table}(${columns})` : 'Optimization';
        header.appendChild(el('span', 'card-title', label));

        const statusEl = el(
            'span',
            'validation-status ' + (validation.improved ? 'improved' : 'not-improved'),
            validation.improved ? '✓ Optimization Validated' : 'Optimization Not Validated'
        );
        header.appendChild(statusEl);
        card.appendChild(header);

        const beforeAfter = el('div', 'before-after');

        const beforeCol = el('div', 'col');
        beforeCol.appendChild(el('div', 'col-label', 'Before'));
        beforeCol.appendChild(el('div', 'col-value', formatMs(validation.beforeBenchmark)));
        beforeAfter.appendChild(beforeCol);

        beforeAfter.appendChild(el('div', 'arrow', '→'));

        const afterCol = el('div', 'col');
        afterCol.appendChild(el('div', 'col-label', 'After'));
        afterCol.appendChild(el('div', 'col-value', formatMs(validation.afterBenchmark)));
        beforeAfter.appendChild(afterCol);

        card.appendChild(beforeAfter);

        card.appendChild(
            el('div', 'improvement-line', formatPercent(validation.executionTimeImprovementPercent) + ' faster')
        );

        card.appendChild(
            el(
                'div',
                'cost-line',
                `Cost ${formatCost(validation.beforeTotalCost)} → ${formatCost(validation.afterTotalCost)} ` +
                `(${formatPercent(validation.costImprovementPercent)})`
            )
        );

        return card;
    }

    function renderValidations(validations) {
        clear(validationsList);
        if (!validations || validations.length === 0) {
            validationsPanel.hidden = true;
            return;
        }
        validationsPanel.hidden = false;
        validations.forEach((validation) => {
            validationsList.appendChild(renderValidationCard(validation));
        });
    }

    function renderAiValidations(aiValidations) {
        clear(aiValidationsList);
        if (!aiValidations || aiValidations.length === 0) {
            aiValidationsPanel.hidden = true;
            return;
        }
        aiValidationsPanel.hidden = false;
        aiValidations.forEach((validation) => {
            aiValidationsList.appendChild(renderValidationCard(validation));
        });
    }

    // -----------------------------------------------------------
    // Rendering: AI recommendation
    // -----------------------------------------------------------

    function renderAiRecommendation(aiRecommendation, hasCandidates) {
        clear(aiContent);
        if (!aiRecommendation) {
            aiContent.appendChild(el('div', 'empty-state neutral', 'No AI recommendation available.'));
            return;
        }

        if (aiRecommendation.summary) {
            aiContent.appendChild(el('p', 'ai-summary', aiRecommendation.summary));
        }

        const recommendations = aiRecommendation.recommendations || [];

        if (recommendations.length === 0) {
            const heading = hasCandidates
                ? 'No further optimization recommended'
                : 'No Automatic Optimization Validated';
            aiContent.appendChild(el('div', 'empty-state neutral', heading));
            return;
        }

        recommendations.forEach((recommendation) => {
            const card = el('div', 'card');

            const header = el('div', 'card-header');
            header.appendChild(el('span', 'badge badge-low', recommendation.type));
            if (recommendation.type !== 'NO_CHANGE') {
                const columns = (recommendation.columns && recommendation.columns.length > 0)
                    ? recommendation.columns.join(', ')
                    : '';
                header.appendChild(el('span', 'card-title', `${recommendation.table || ''}(${columns})`));
            }
            card.appendChild(header);

            if (recommendation.reasoning) {
                card.appendChild(el('div', 'detail-label', 'Reasoning'));
                card.appendChild(el('p', 'reason-text', recommendation.reasoning));
            }

            aiContent.appendChild(card);
        });
    }

    // -----------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------

    function showError(title, message) {
        errorTitle.textContent = title;
        errorMessage.textContent = message;
        errorBanner.hidden = false;
        results.hidden = true;
    }

    function hideError() {
        errorBanner.hidden = true;
    }

    async function handleErrorResponse(response) {
        if (response.status === 400) {
            try {
                const body = await response.json();
                if (body && body.error === 'UNSAFE_SQL') {
                    showError('Query Rejected', body.message || 'Only a single read-only SQL statement is allowed.');
                    return;
                }
            } catch (parseError) {
                // fall through to generic handling below
            }
            showError('Query Rejected', 'The submitted query could not be validated.');
            return;
        }

        if (response.status >= 500) {
            showError('Query analysis failed.', 'Please check the server logs.');
            return;
        }

        showError('Query analysis failed.', 'Please check the server logs.');
    }

    // -----------------------------------------------------------
    // Analyze flow
    // -----------------------------------------------------------

    function setAnalyzing(isAnalyzing) {
        analyzeBtn.disabled = isAnalyzing;
        analyzeSpinner.hidden = !isAnalyzing;
        analyzeLabel.textContent = isAnalyzing ? 'Analyzing…' : 'Analyze Query';
        loadingBanner.hidden = !isAnalyzing;
    }

    async function analyzeQuery() {
        const sql = sqlInput.value.trim();

        if (!sql) {
            showError('Query Required', 'Enter a SQL statement before running analysis.');
            return;
        }

        hideError();
        results.hidden = true;
        setAnalyzing(true);

        try {
            const response = await fetch(ANALYSIS_ENDPOINT, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sql })
            });

            if (!response.ok) {
                await handleErrorResponse(response);
                return;
            }

            const data = await response.json();
            renderResults(data);
        } catch (networkError) {
            showError('Connection Error', 'Unable to reach QueryPilot backend.');
        } finally {
            setAnalyzing(false);
        }
    }

    function renderResults(data) {
        hideError();

        renderMetrics(data);
        renderPlanSummary(data.plan);
        renderIssues(data.issues);
        renderJoins(data.joins);
        renderOperations(data.operations);
        renderCandidates(data.candidates);
        renderValidations(data.validations);
        renderAiRecommendation(data.aiRecommendation, (data.candidates || []).length > 0);
        renderAiValidations(data.aiValidations);

        results.hidden = false;
    }

    // -----------------------------------------------------------
    // Wiring
    // -----------------------------------------------------------

    analyzeBtn.addEventListener('click', analyzeQuery);

    clearBtn.addEventListener('click', () => {
        sqlInput.value = '';
        hideError();
        results.hidden = true;
        sqlInput.focus();
    });

    checkSandboxStatus();
})();
