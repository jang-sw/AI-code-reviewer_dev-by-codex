<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<c:url var="monitoringUrl" value="/admin/monitoring"/>
<c:url var="failedProjectsUrl" value="/admin/operations"><c:param name="filter" value="FAILED"/></c:url>
<c:url var="delayedRequestsUrl" value="/admin/operations"><c:param name="filter" value="REQUEST_DELAYED"/></c:url>
<c:url var="queuedRequestsUrl" value="/admin/operations"><c:param name="filter" value="QUEUED"/></c:url>
<c:url var="operationsUrl" value="/admin/operations"/>
<section class="page-heading"><p class="eyebrow">ADMINISTRATION</p><h1>서버 상태</h1><p>최근 수집한 운영 요약으로 확인할 실패와 지연을 살펴보세요. 이 화면의 알림은 이메일이나 푸시로 발송되지 않습니다.</p><div class="actions"><a class="button button-secondary" href="<c:out value='${monitoringUrl}'/>">화면 새로고침</a><a href="<c:out value='${operationsUrl}'/>">프로젝트별 운영 상태</a></div></section>
<section id="monitoring-observation" class="card" data-observation-status="<c:out value='${monitoring.status}'/>" aria-labelledby="monitoring-observation-heading">
  <h2 id="monitoring-observation-heading">관측 상태</h2>
  <p class="monitoring-status" role="status"><c:choose><c:when test="${monitoring.status == 'READY'}"><span class="badge badge-success">최신 관측 사용 가능</span></c:when><c:when test="${monitoring.status == 'STARTING'}"><span class="badge badge-warning">첫 수집 대기</span> 아직 운영 수치를 수집하지 못했습니다.</c:when><c:when test="${monitoring.status == 'STALE'}"><span class="badge badge-warning">관측이 오래됨</span> 수집 주기를 확인해 주세요. 이전 수치는 현재 상태로 표시하지 않습니다.</c:when><c:otherwise><span class="badge badge-danger">수집 실패</span> 현재 운영 수치를 확인할 수 없습니다. 서버와 데이터베이스 상태를 확인해 주세요.</c:otherwise></c:choose></p>
  <dl class="monitoring-times"><dt>마지막 성공 관측 (UTC)</dt><dd><c:choose><c:when test="${not empty monitoring.observedAt}"><time datetime="<c:out value='${monitoring.observedAt}'/>"><c:out value="${monitoringObservedAtLabel}"/></time> · <c:out value="${monitoring.ageSeconds}"/>초 전 기준</c:when><c:otherwise>아직 성공한 관측 없음</c:otherwise></c:choose></dd><dt>마지막 수집 시도 (UTC)</dt><dd><c:choose><c:when test="${not empty monitoring.lastAttemptAt}"><time datetime="<c:out value='${monitoring.lastAttemptAt}'/>"><c:out value="${monitoringLastAttemptAtLabel}"/></time></c:when><c:otherwise>아직 시도 기록 없음</c:otherwise></c:choose></dd></dl>
  <p class="hint">수치는 별도 주기로 수집하며 화면을 새로고침해도 즉시 재집계하지 않습니다. 경과 시간은 이 화면을 조회한 시점의 값입니다.</p>
</section>
<c:choose><c:when test="${monitoring.available}">
  <section class="card" aria-labelledby="monitoring-alerts-heading"><h2 id="monitoring-alerts-heading">화면 알림</h2>
    <c:if test="${monitoring.latestFailedProjects > 0}"><div class="notice error monitoring-alert"><p><strong>최근 실행이 실패한 프로젝트 <c:out value="${monitoring.latestFailedProjects}"/>개</strong></p><p>새 실행이 시작되면 이전 실패는 이 집계에서 제외합니다.</p><a href="<c:out value='${failedProjectsUrl}'/>">실패한 프로젝트 확인</a></div></c:if>
    <c:if test="${monitoring.delayedActiveRequests > 0}"><div class="notice monitoring-alert"><p><strong>오래된 미완료 요청 <c:out value="${monitoring.delayedActiveRequests}"/>건</strong></p><p>접수 후 <c:out value="${monitoring.delayedAfterMinutes}"/>분 이상 대기 또는 처리 중으로 기록된 요청입니다. 재시작 복구 시간도 포함됩니다.</p><a href="<c:out value='${delayedRequestsUrl}'/>">지연된 요청 확인</a></div></c:if>
    <c:if test="${monitoring.latestFailedProjects == 0 and monitoring.delayedActiveRequests == 0}"><p>이 관측에서 최근 실행 실패나 오래된 미완료 요청은 없습니다.</p></c:if>
    <p class="hint">완료 예정 시각이나 실행 보장 기한을 뜻하지 않습니다. 실패·지연이 없어도 미실행 등 다른 확인 대상은 프로젝트별 운영 상태에서 확인하세요.</p>
  </section>
  <div id="monitoring-counts" class="stats-grid monitoring-stats" aria-label="최근 관측 운영 수치">
    <article class="stat-card"><span>승인된 프로젝트</span><strong><c:out value="${monitoring.projectCounts.APPROVED}"/></strong></article>
    <a class="stat-card" href="<c:out value='${queuedRequestsUrl}'/>"><span>실행 대기 요청</span><strong><c:out value="${monitoring.requestCounts.QUEUED}"/></strong></a>
    <article class="stat-card"><span>처리 중으로 기록된 요청</span><strong><c:out value="${monitoring.requestCounts.RUNNING}"/></strong></article>
  </div>
  <section class="card" aria-labelledby="monitoring-scope-heading"><h2 id="monitoring-scope-heading">집계 범위</h2><p>프로젝트와 요청 수치는 이 서버가 연결한 데이터베이스 전체의 기록입니다. 요청은 프로젝트마다 보존된 마지막 상태 한 건씩 집계하며 누적 실행 횟수가 아닙니다.</p>
    <dl><dt>프로젝트 승인 대기</dt><dd><c:out value="${monitoring.projectCounts.PENDING}"/>개</dd><dt>반려 / 일시 중지</dt><dd><c:out value="${monitoring.projectCounts.REJECTED}"/>개 / <c:out value="${monitoring.projectCounts.PAUSED}"/>개</dd><dt>마지막 요청 완료 / 실패 / 취소</dt><dd><c:out value="${monitoring.requestCounts.SUCCEEDED}"/>건 / <c:out value="${monitoring.requestCounts.FAILED}"/>건 / <c:out value="${monitoring.requestCounts.CANCELLED}"/>건</dd><dt>가장 오래된 미완료 요청 접수 (UTC)</dt><dd><c:choose><c:when test="${not empty monitoring.oldestActiveRequestedAt}"><time datetime="<c:out value='${monitoring.oldestActiveRequestedAt}'/>"><c:out value="${monitoringOldestActiveRequestedAtLabel}"/></time></c:when><c:otherwise>미완료 요청 없음</c:otherwise></c:choose></dd></dl>
    <p class="hint">처리 중이라는 기록은 실제 작업자가 실행 중임을 보장하지 않습니다. 장기 실행이나 재시작 복구 대기일 수 있습니다.</p>
  </section>
</c:when><c:otherwise><section class="card" aria-labelledby="monitoring-unavailable-heading"><h2 id="monitoring-unavailable-heading">운영 수치 확인 불가</h2><p>현재 사용할 수 있는 관측이 없어 수치와 실패·지연 판정을 표시하지 않습니다. 정상 또는 0건으로 해석하지 마세요.</p><a href="<c:out value='${operationsUrl}'/>">프로젝트별 상태 직접 확인</a></section></c:otherwise></c:choose>
<section class="card" aria-labelledby="monitoring-server-heading"><h2 id="monitoring-server-heading">현재 서버 설정</h2><p>지금 접속한 서버의 설정입니다. 다른 서버의 설정이나 실제 실행 상태와 다를 수 있습니다.</p><dl><dt>리뷰 요청 처리</dt><dd><c:choose><c:when test="${monitoring.currentServerWorkerEnabled}">켜짐</c:when><c:otherwise>일시 중지 · 요청은 저장되며 이 서버의 처리를 재개하면 이어서 처리합니다.</c:otherwise></c:choose></dd><dt>새 예약 요청 생성</dt><dd><c:choose><c:when test="${monitoring.currentServerSchedulerEnabled}">켜짐</c:when><c:otherwise>꺼짐 · 이미 접수된 요청과 직접 요청의 처리를 중지하는 설정은 아닙니다.</c:otherwise></c:choose></dd></dl></section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
