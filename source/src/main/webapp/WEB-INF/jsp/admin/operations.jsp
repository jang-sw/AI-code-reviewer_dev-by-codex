<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<section class="page-heading"><p class="eyebrow">ADMINISTRATION</p><h1>운영 현황</h1><p>리뷰 실행의 실패와 경과 시간을 확인하고 프로젝트별 기록으로 이동하세요.</p></section>
<section class="card" aria-labelledby="operations-observation-heading">
  <h2 id="operations-observation-heading">조회 기준</h2>
  <p>서버 조회 시각 (UTC): <time><c:out value="${operations.observedAt}"/></time></p>
  <p>마지막 실행 시작 이후 <strong><c:out value="${operations.staleAfterMinutes}"/>분 이상</strong> 지난 승인 프로젝트와 최근 실행이 실패한 프로젝트를 확인합니다.</p>
  <details><summary>경과 시간과 실행 상태 읽는 법</summary><p>경과 시간은 실행 시작 시각을 기준으로 하며, 완료 예정 시각이나 실행 보장 기한을 뜻하지 않습니다.</p><p class="hint">진행 중으로 기록된 리뷰는 장기 실행 또는 종료 상태 미기록일 수 있으므로 실제 실행 여부를 별도로 확인해 주세요. 실행 기록이 없으면 실제 대기 시간을 계산하지 않습니다. 다른 프로세스의 실행 여부나 대기열 전체를 이 목록만으로 판단할 수 없습니다.</p></details>
  <c:if test="${not scheduledReviewEnabled}"><p class="notice">현재 자동 리뷰가 꺼져 있습니다. 승인된 프로젝트도 직접 리뷰를 요청해야 실행됩니다.</p></c:if>
</section>
<section class="card">
  <c:url var="operationsAction" value="/admin/operations"/>
  <form method="get" action="<c:out value='${operationsAction}'/>" class="toolbar">
    <label for="operations-filter">확인할 상태</label>
    <select id="operations-filter" name="filter">
      <option value="ATTENTION" ${operations.filter == 'ATTENTION' ? 'selected' : ''}>확인 대상 전체</option>
      <option value="FAILED" ${operations.filter == 'FAILED' ? 'selected' : ''}>최근 실행 실패</option>
      <option value="STALE" ${operations.filter == 'STALE' ? 'selected' : ''}>최근 시작 오래됨</option>
      <option value="NEVER_RUN" ${operations.filter == 'NEVER_RUN' ? 'selected' : ''}>승인 후 실행 기록 없음</option>
    </select><button type="submit">조회</button>
  </form>
  <p class="hint">미실행 먼저 · 실행 시작이 오래된 순 · 페이지당 50건. 최근 실행 실패는 프로젝트 상태와 무관하게 표시하며, 이후 실행이 성공하면 실패 목록에서 제외합니다.</p>
</section>
<section class="card" aria-labelledby="operations-projects-heading"><h2 id="operations-projects-heading">확인할 프로젝트</h2>
  <c:choose><c:when test="${empty operations.projects}"><p class="empty-state">이 페이지에서 조회 조건에 해당하는 프로젝트가 없습니다. 필터와 페이지를 확인하세요.</p></c:when><c:otherwise>
    <p id="operations-scroll-hint" class="hint review-history-scroll-hint">표를 좌우로 이동해 확인하세요. 키보드는 표를 선택한 뒤 좌우 방향키를 사용하세요.</p>
    <div class="table-wrap" tabindex="0" role="region" aria-label="운영 현황 표, 좌우 스크롤" aria-describedby="operations-scroll-hint"><table class="operations-table"><thead><tr><th scope="col">프로젝트</th><th scope="col">확인 이유</th><th scope="col">최근 실행 기록</th><th scope="col">시작 시각 (UTC)</th><th scope="col">시작 후 경과</th><th scope="col">기록 확인</th></tr></thead><tbody>
      <c:forEach var="observation" items="${operations.projects}"><tr>
        <td><strong><c:out value="${observation.projectName}"/></strong><br><ui:status value="${observation.projectStatus}"/></td>
        <td><c:if test="${observation.failed}"><span class="badge badge-danger">최근 실행 실패</span> </c:if><c:if test="${observation.stale}"><span class="badge badge-warning">최근 시작 오래됨</span> </c:if><c:if test="${observation.neverRun}"><span class="badge badge-warning">실행 기록 없음</span></c:if></td>
        <td><c:choose><c:when test="${empty observation.runId}">미실행</c:when><c:otherwise><ui:status value="${observation.runStatus}"/></c:otherwise></c:choose></td>
        <td class="operations-timestamp"><c:out value="${observation.startedAt}" default="—"/></td>
        <td><c:choose><c:when test="${empty observation.runId}">산정하지 않음</c:when><c:otherwise><c:out value="${observation.elapsedMinutes}"/>분</c:otherwise></c:choose></td>
        <td><c:url var="operationProjectUrl" value="/projects/${observation.projectId}"/><a href="<c:out value='${operationProjectUrl}'/>">프로젝트</a><c:if test="${not empty observation.runId}"><br><c:url var="operationReviewsUrl" value="/reviews"><c:param name="projectId" value="${observation.projectId}"/></c:url><a href="<c:out value='${operationReviewsUrl}'/>">리뷰 기록</a></c:if></td>
      </tr></c:forEach>
    </tbody></table></div>
  </c:otherwise></c:choose>
</section>
<nav class="pagination" aria-label="운영 현황 페이지">
  <c:if test="${operations.page > 0}"><c:url var="previousOperations" value="/admin/operations"><c:param name="filter" value="${operations.filter}"/><c:param name="page" value="${operations.page - 1}"/></c:url><a href="<c:out value='${previousOperations}'/>">이전</a></c:if>
  <span><c:out value="${operations.page + 1}"/> 페이지</span>
  <c:if test="${operations.hasNext}"><c:url var="nextOperations" value="/admin/operations"><c:param name="filter" value="${operations.filter}"/><c:param name="page" value="${operations.page + 1}"/></c:url><a href="<c:out value='${nextOperations}'/>">다음</a></c:if>
</nav>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
