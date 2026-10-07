<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<c:url var="projectListUrl" value="/projects"/><c:url var="reviewsUrl" value="/reviews"><c:param name="projectId" value="${project.id}"/></c:url>
<section class="page-heading"><a href="<c:out value='${projectListUrl}'/>">← 프로젝트 목록</a><h1><c:out value="${project.name}"/></h1><p class="project-meta"><c:out value="${project.repositoryUrl}"/></p></section>
<%@ include file="/WEB-INF/jsp/fragments/review-request.jspf" %>
<section class="card" aria-labelledby="project-state-heading">
  <h2 id="project-state-heading"><c:choose><c:when test="${project.status == 'PENDING'}">승인을 기다리고 있어요</c:when><c:when test="${project.status == 'APPROVED'}"><c:choose><c:when test="${reviewRequest.active}">리뷰 요청이 접수되었어요</c:when><c:when test="${reviewRequest.rateLimitExhausted}">직접 리뷰를 다시 요청해 주세요</c:when><c:when test="${not reviewWorkerEnabled}">리뷰 요청을 저장할 수 있어요</c:when><c:when test="${scheduledReviewEnabled}">자동 리뷰가 켜져 있어요</c:when><c:otherwise>프로젝트가 승인되었어요</c:otherwise></c:choose></c:when><c:when test="${project.status == 'PAUSED'}">리뷰가 일시 중지되었어요</c:when><c:otherwise>등록이 승인되지 않았어요</c:otherwise></c:choose></h2>
  <p><c:choose><c:when test="${project.status == 'PENDING'}"><c:choose><c:when test="${scheduledReviewEnabled}">관리자가 승인하면 예약 일정에 따라 과거 변경부터 순서대로 리뷰합니다.</c:when><c:otherwise>관리자가 승인하면 ‘지금 리뷰하기’를 눌러 과거 변경부터 검토할 수 있습니다. 현재 자동 리뷰는 꺼져 있습니다.</c:otherwise></c:choose></c:when><c:when test="${project.status == 'APPROVED'}"><c:choose><c:when test="${reviewRequest.active}">이미 리뷰 요청이 접수되어 있습니다. 위의 ‘최근 리뷰 요청’에서 상태를 확인하세요.</c:when><c:when test="${reviewRequest.rateLimitExhausted}">외부 서비스 호출 제한으로 자동 재시도를 중단했습니다. 예약으로 다시 접수되지 않으므로 서비스 상태를 확인한 뒤 ‘지금 리뷰하기’를 눌러 주세요.</c:when><c:when test="${not reviewWorkerEnabled}">현재 서버의 리뷰 처리는 일시 중지되어 있지만 ‘지금 리뷰하기’로 요청을 저장할 수 있습니다. 이 서버는 관리자가 처리를 재개하면 이어서 처리하며, 다른 서버에서는 처리가 계속될 수 있습니다.</c:when><c:when test="${scheduledReviewEnabled}">예약 일정에 따라 코드 변경을 확인합니다. 기다리지 않고 지금 리뷰를 요청할 수도 있습니다.</c:when><c:otherwise>현재 자동 리뷰는 꺼져 있습니다. 아래 ‘지금 리뷰하기’를 눌러 리뷰를 요청하세요.</c:otherwise></c:choose></c:when><c:when test="${project.status == 'PAUSED'}">지금은 새로운 리뷰를 시작하지 않습니다. <c:if test="${not isAdmin}">재개하려면 관리자에게 요청해 주세요.</c:if></c:when><c:otherwise>저장소 주소와 접근 권한을 확인하고 관리자에게 문의해 주세요.</c:otherwise></c:choose></p>
  <c:if test="${not empty project.reviewStatus}"><p class="project-next-action">최근 실행 기록: <c:choose><c:when test="${project.reviewStatus == 'RUNNING'}">진행 중으로 기록되어 있습니다. 현재 요청 상태와 리뷰 기록을 함께 확인하세요.</c:when><c:when test="${project.reviewStatus == 'FAILED'}">완료하지 못한 실행입니다. <c:choose><c:when test="${reviewRequest.active}">현재 접수된 요청은 위에서 별도로 확인할 수 있습니다.</c:when><c:otherwise>리뷰 기록에서 원인을 확인한 뒤 다시 요청해 주세요.</c:otherwise></c:choose></c:when><c:otherwise>이번 실행이 완료되었습니다. 리뷰 기록에서 결과를 확인하세요.</c:otherwise></c:choose></p></c:if>
  <c:if test="${not scheduledReviewEnabled}"><p class="hint">새 예약 요청 생성은 꺼져 있습니다. 이미 접수된 요청은 유지되며 리뷰 처리가 켜져 있으면 계속 처리됩니다.</p></c:if>
  <div class="actions"><c:if test="${project.approved}"><c:url var="reviewAction" value="/projects/${project.id}/review"/><form method="post" action="<c:out value='${reviewAction}'/>"><input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><button type="submit" ${reviewRequest.active ? 'disabled' : ''}><c:choose><c:when test="${reviewRequest.active}">리뷰 요청 처리 대기 중</c:when><c:otherwise>지금 리뷰하기</c:otherwise></c:choose></button></form></c:if><c:if test="${project.approved or not empty project.approvedAt}"><a href="<c:out value='${reviewsUrl}'/>">리뷰 기록 보기</a></c:if></div>
  <c:if test="${project.status == 'PAUSED' and reviewRequest.active}"><p class="hint">저장된 요청은 처리 재확인 시 프로젝트 상태에 따라 취소됩니다. 실행 중이라면 저장 전에 승인 상태를 다시 확인합니다.</p></c:if>
</section>
<c:if test="${isAdmin}"><section class="card"><h2><c:choose><c:when test="${project.status == 'PENDING'}">등록 승인</c:when><c:otherwise>리뷰 관리</c:otherwise></c:choose></h2><p>저장소 주소와 등록자를 확인해 주세요. <c:choose><c:when test="${reviewRequest.rateLimitExhausted}">호출 제한으로 중단된 요청은 승인이나 리뷰 재개만으로 다시 접수되지 않습니다. 서비스 상태를 확인한 뒤 직접 리뷰를 요청해 주세요.</c:when><c:when test="${scheduledReviewEnabled}">승인하면 예약 일정에 따라 리뷰를 시작합니다.</c:when><c:otherwise>승인하면 사용자가 직접 리뷰를 요청할 수 있습니다.</c:otherwise></c:choose></p><div class="actions">
  <c:if test="${not project.approved}"><c:url var="approveAction" value="/admin/projects/${project.id}/approve"/><form method="post" action="<c:out value='${approveAction}'/>"><input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><button type="submit"><c:choose><c:when test="${project.status == 'PAUSED'}">리뷰 재개</c:when><c:otherwise>프로젝트 승인</c:otherwise></c:choose></button></form></c:if>
  <c:if test="${project.status == 'PENDING'}"><c:url var="rejectAction" value="/admin/projects/${project.id}/reject"/><form method="post" action="<c:out value='${rejectAction}'/>"><input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><button type="submit" class="button-secondary">등록 반려</button></form></c:if>
  <c:if test="${project.approved}"><c:url var="pauseAction" value="/admin/projects/${project.id}/pause"/><form method="post" action="<c:out value='${pauseAction}'/>"><input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><button type="submit" class="button-secondary">리뷰 일시 중지</button></form></c:if>
</div></section></c:if>
<c:if test="${isAdmin and not empty branchCorrectionError}"><section class="notice error" id="branch-correction-error" role="alert"><strong>브랜치를 정정하지 않았습니다.</strong> <c:out value="${branchCorrectionError}"/><c:if test="${branchCorrectionCleared}"> 너무 긴 값이나 제어문자·자격증명 형태가 포함된 입력은 다시 표시하지 않습니다. 비밀정보를 제외하고 다시 입력해 주세요.</c:if></section></c:if>
<c:if test="${isAdmin and (project.status == 'PENDING' or project.status == 'REJECTED' or project.status == 'PAUSED')}">
<section class="card"><details id="branch-correction" ${not empty branchCorrectionError ? 'open' : ''}><summary>리뷰 브랜치 정정</summary>
  <p>브랜치를 잘못 입력했을 때 사용하세요. 저장소 주소·등록자·기존 리뷰와 이슈는 그대로 유지하고, 다음 리뷰의 진행 기준만 초기화합니다. 이전 브랜치에만 있는 리뷰와 이슈도 남습니다.</p>
  <p>현재 브랜치: <strong><c:out value="${project.reviewBranch}" default="저장소 기본 브랜치"/></strong><br>현재 진행 기준: <code><c:out value="${project.lastReviewedSha}" default="없음"/></code></p>
  <p class="hint">실행 중이거나 대기 중인 요청이 있으면 정정할 수 없습니다. 정정 후에도 현재 승인·일시 중지 상태는 유지됩니다. 관리자가 별도로 승인하거나 리뷰를 재개하면 새 브랜치 전체 이력 중 아직 저장하지 않은 커밋을 검토합니다. 호출 제한으로 자동 재시도가 중단된 요청은 직접 다시 접수해야 합니다.</p>
  <c:url var="branchCorrectionAction" value="/admin/projects/${project.id}/branch"/>
  <form method="post" action="<c:out value='${branchCorrectionAction}'/>" class="form-stack">
    <input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>">
    <input type="hidden" name="expectedBranch" value="<c:out value='${project.reviewBranch}'/>">
    <input type="hidden" name="expectedCursor" value="<c:out value='${project.lastReviewedSha}'/>">
    <label for="corrected-branch">정정할 브랜치 (선택)</label>
    <input id="corrected-branch" name="reviewBranch" maxlength="255" autocomplete="off" value="<c:out value='${branchCorrectionForm.reviewBranch}'/>" aria-describedby="corrected-branch-help${not empty branchCorrectionError ? ' branch-correction-error' : ''}">
    <p id="corrected-branch-help" class="hint">비워 두면 저장소의 기본 브랜치를 사용합니다. 브랜치가 실제 저장소에 존재하는지는 다음 리뷰에서 확인합니다.</p>
    <label for="branch-correction-reason">정정 사유</label>
    <input id="branch-correction-reason" name="reason" required minlength="5" maxlength="500" autocomplete="off" value="<c:out value='${branchCorrectionForm.reason}'/>" aria-describedby="branch-correction-reason-help${not empty branchCorrectionError ? ' branch-correction-error' : ''}">
    <p id="branch-correction-reason-help" class="hint">앞뒤 공백을 제외하고 5자 이상, 전체 500자 이하. 사유와 이전·새 브랜치 및 진행 기준은 관리자 감사 기록에 남습니다. 비밀번호·토큰·소스코드 등 비밀정보를 입력하지 마세요.</p>
    <label class="inline"><input type="checkbox" name="confirmed" value="true" required>현재 브랜치와 진행 기준을 확인했으며, 기존 기록을 보존하고 진행 기준을 초기화하는 데 동의합니다.</label>
    <button type="submit" class="button-secondary">기록을 보존하고 브랜치 정정</button>
  </form>
</details></section>
</c:if>
<c:if test="${isAdmin and project.status == 'PAUSED' and not empty project.lastReviewedSha}">
<section class="card"><details><summary>Git 이력 변경 후 리뷰 진행 기준 복구</summary>
  <p>강제 푸시 등으로 저장된 기준 커밋이 현재 브랜치 이력에 없을 때 사용하세요. 기존 커밋 리뷰와 이슈는 보존하고 진행 기준만 초기화합니다.</p>
  <p>프로젝트는 일시 중지 상태를 유지합니다. 관리자가 리뷰를 재개한 뒤 <c:choose><c:when test="${reviewRequest.rateLimitExhausted}">서비스 상태를 확인하고 직접 리뷰 요청으로</c:when><c:when test="${scheduledReviewEnabled}">예약 실행 또는 직접 리뷰 요청으로</c:when><c:otherwise>직접 리뷰 요청으로 (현재 자동 리뷰 꺼짐)</c:otherwise></c:choose> 현재 브랜치 전체 이력에서 아직 리뷰하지 않은 커밋을 순서대로 처리합니다. 현재 이력에서 사라진 커밋의 리뷰와 이슈도 기록에 남습니다.</p>
  <p class="hint">실행 중인 리뷰가 있으면 복구할 수 없습니다. 종료 후 새로고침하여 다시 시도하세요.</p>
  <p>현재 진행 기준: <code class="commit-sha"><c:out value="${project.lastReviewedSha}"/></code></p>
  <c:url var="recoveryAction" value="/admin/projects/${project.id}/review-progress/reset"/>
  <form method="post" action="<c:out value='${recoveryAction}'/>" class="form-stack">
    <input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>">
    <input type="hidden" name="expectedCursor" value="<c:out value='${project.lastReviewedSha}'/>">
    <label for="recovery-repository">확인을 위해 저장소 주소를 그대로 입력하세요</label>
    <p id="recovery-repository-help" class="hint"><c:out value="${project.repositoryUrl}"/></p>
    <input id="recovery-repository" name="repositoryUrl" type="url" required maxlength="2048" autocomplete="off" aria-describedby="recovery-repository-help">
    <label for="recovery-reason">복구 사유</label>
    <input id="recovery-reason" name="reason" required minlength="5" maxlength="500" autocomplete="off" aria-describedby="recovery-reason-help">
    <p id="recovery-reason-help" class="hint">5~500자, 줄바꿈 없이 입력하세요. 사유와 이전 기준 커밋은 감사 기록에 저장됩니다. 비밀번호·토큰·소스코드 등 비밀정보를 입력하지 마세요.</p>
    <button type="submit" class="button-secondary">기존 기록을 보존하고 진행 기준 초기화</button>
  </form>
</details></section>
</c:if>
<section class="card"><h2>프로젝트 정보</h2><dl><dt>등록자</dt><dd><c:out value="${project.ownerUsername}"/></dd><dt>저장소</dt><dd><c:out value="${project.provider}"/> · <c:out value="${project.repositoryPath}"/></dd><dt>리뷰 브랜치</dt><dd><c:out value="${project.reviewBranch}" default="저장소 기본 브랜치"/></dd><dt>리뷰 실행</dt><dd><c:choose><c:when test="${reviewRequest.rateLimitExhausted}">직접 재요청 필요 (호출 제한 자동 재시도 중단)</c:when><c:when test="${scheduledReviewEnabled}">예약 일정 또는 직접 요청</c:when><c:otherwise>직접 요청 (자동 리뷰 꺼짐)</c:otherwise></c:choose></dd><dt>등록 시각 (UTC)</dt><dd><c:out value="${project.createdAt}"/></dd></dl><p class="muted">수정 권고는 작성자와 연결된 계정의 이슈함으로 전달합니다. 연결된 계정이 없으면 프로젝트 등록자에게 전달합니다.</p></section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
