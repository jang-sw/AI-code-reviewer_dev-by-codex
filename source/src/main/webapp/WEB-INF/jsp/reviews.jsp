<%@ page pageEncoding="UTF-8" %>
<%@ include file="fragments/header.jspf" %>
<section class="page-heading"><p class="eyebrow">리뷰 진행 현황</p><h1><c:out value="${projectName}"/> 리뷰 기록</h1><p>과거 변경부터 순서대로 검토합니다. 이력이 많으면 여러 번 나누어 처리합니다. <c:choose><c:when test="${reviewRequest.active}">이미 리뷰 요청이 접수되어 있습니다. 아래의 ‘최근 리뷰 요청’에서 상태를 확인하세요.</c:when><c:when test="${projectStatus != 'APPROVED'}">다음 요청 전에 프로젝트의 승인 상태를 확인해 주세요.</c:when><c:when test="${reviewRequest.rateLimitExhausted}">외부 서비스 호출 제한으로 자동 재시도를 중단했습니다. 예약으로 다시 접수되지 않으므로 서비스 상태를 확인한 뒤 직접 리뷰를 요청해 주세요.</c:when><c:when test="${not reviewWorkerEnabled}">현재 서버의 리뷰 처리는 일시 중지되어 있지만 ‘지금 리뷰 요청’으로 요청을 저장할 수 있습니다. 이 서버는 관리자가 처리를 재개하면 이어서 처리하며, 다른 서버에서는 처리가 계속될 수 있습니다.</c:when><c:when test="${scheduledReviewEnabled}">남은 이력은 다음 예약 요청에서 이어서 처리합니다.</c:when><c:otherwise>자동 리뷰가 꺼져 있으므로 남은 이력을 검토하려면 직접 리뷰를 요청해 주세요.</c:otherwise></c:choose></p></section>
<c:if test="${not scheduledReviewEnabled}"><p class="hint">새 예약 요청 생성은 꺼져 있습니다. 이미 접수된 요청은 유지되며 리뷰 처리가 켜져 있으면 계속 처리됩니다.</p></c:if>
<%@ include file="fragments/review-request.jspf" %>
<div class="toolbar">
  <c:url var="projectUrl" value="/projects/${projectId}"/><a href="<c:out value='${projectUrl}'/>">프로젝트로 돌아가기</a>
  <c:if test="${projectStatus == 'APPROVED'}"><c:url var="reviewAction" value="/projects/${projectId}/review"/><form method="post" action="<c:out value='${reviewAction}'/>"><input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><button type="submit" ${reviewRequest.active ? 'disabled' : ''}><c:choose><c:when test="${reviewRequest.active}">리뷰 요청 처리 대기 중</c:when><c:otherwise>지금 리뷰 요청</c:otherwise></c:choose></button></form></c:if>
</div>
<section class="panel"><h2>리뷰 처리가 진행된 기준 커밋</h2><c:choose><c:when test="${empty cursor}"><p>아직 모든 선행 변경의 리뷰 처리를 마친 기준 커밋이 없습니다. 이미 저장된 커밋 리뷰는 다음 실행에서 재사용합니다.</p></c:when><c:otherwise><code class="commit-sha"><c:out value="${cursor}"/></code></c:otherwise></c:choose><p class="hint">수동 확인 이슈를 배정한 커밋도 진행 기준에 포함됩니다. 담당자의 확인 완료 여부는 이슈함에서 확인하세요.</p></section>
<section class="panel" id="runs"><h2>실행 기록</h2><p class="muted">최신순 · 페이지당 50건</p>
  <c:choose><c:when test="${empty runs}"><p class="empty-state"><c:choose><c:when test="${runPage > 0}">이 페이지에는 실행 기록이 없습니다. 이전 페이지를 확인하세요.</c:when><c:otherwise>아직 리뷰 실행 기록이 없습니다. 현재 요청 상태와 프로젝트 승인 상태를 확인해 주세요.</c:otherwise></c:choose></p></c:when><c:otherwise>
  <p id="review-history-scroll-hint" class="hint review-history-scroll-hint">표를 좌우로 이동해 확인하세요. 키보드는 표를 선택한 뒤 좌우 방향키를 사용하세요.</p>
  <div class="table-wrap review-history-scroll" tabindex="0" role="region" aria-label="리뷰 실행 기록 표, 좌우 스크롤" aria-describedby="review-history-scroll-hint"><table class="review-history-table"><thead><tr><th scope="col">실행</th><th scope="col">상태</th><th scope="col">저장한 커밋 수</th><th scope="col">시작</th><th scope="col">종료 / 오류</th></tr></thead><tbody>
    <c:forEach items="${runs}" var="run"><tr><td>#<c:out value="${run.id}"/></td><td><ui:status value="${run.status}"/></td><td><c:out value="${run.reviewed_commits}"/></td><td class="review-timestamp"><c:out value="${run.started_at}"/></td><td class="review-result"><span class="review-timestamp"><c:out value="${run.finished_at}"/></span><c:if test="${not empty run.error_message}"><p class="error-text"><c:out value="${run.error_message}"/></p></c:if></td></tr></c:forEach>
  </tbody></table></div></c:otherwise></c:choose>
  <nav class="pagination" aria-label="실행 기록 페이지"><c:if test="${runPage > 0}"><c:url var="previousRunPage" value="/reviews"><c:param name="projectId" value="${projectId}"/><c:param name="commitPage" value="${commitPage}"/><c:param name="runPage" value="${runPage - 1}"/></c:url><a href="<c:out value='${previousRunPage}'/>#runs">이전</a></c:if><span><c:out value="${runPage + 1}"/> 페이지</span><c:if test="${hasNextRunPage}"><c:url var="nextRunPage" value="/reviews"><c:param name="projectId" value="${projectId}"/><c:param name="commitPage" value="${commitPage}"/><c:param name="runPage" value="${runPage + 1}"/></c:url><a href="<c:out value='${nextRunPage}'/>#runs">다음</a></c:if></nav>
  <c:if test="${runPage == maxHistoryPage}"><p class="muted">조회 가능한 마지막 페이지입니다.</p></c:if>
</section>
<section class="panel" id="commits"><h2>리뷰 커밋 기록</h2><p class="muted">최신순 · 페이지당 50건</p>
  <c:choose><c:when test="${empty commits}"><p class="empty-state"><c:choose><c:when test="${commitPage > 0}">이 페이지에는 커밋 리뷰가 없습니다. 이전 페이지를 확인하세요.</c:when><c:otherwise>완료된 커밋 리뷰가 없습니다.</c:otherwise></c:choose></p></c:when><c:otherwise>
    <c:forEach items="${commits}" var="commit"><article class="review-card">
      <div class="section-heading"><code class="commit-sha"><c:out value="${commit.commit_sha}"/></code><span>이슈 <c:out value="${commit.issue_count}"/>건</span></div>
      <c:if test="${not empty commit.commit_url}"><p><a href="<c:out value='${commit.commit_url}'/>" target="_blank" rel="noopener noreferrer">원본 커밋 보기</a></p></c:if>
      <p><span class="badge"><c:choose><c:when test="${commit.coverage_type == 'EMPTY'}">파일 변경 없음 · AI 본문 검토 없음</c:when><c:when test="${commit.coverage_type == 'METADATA_ONLY'}">메타데이터 변경 · 수동 확인 필요</c:when><c:when test="${commit.coverage_type == 'MANUAL_ONLY'}">수동 확인 이슈 배정 · AI 본문 검토 없음</c:when><c:otherwise>AI 본문 리뷰<c:if test="${not empty commit.coverage_details}"> · 메타데이터 변경 포함</c:if></c:otherwise></c:choose></span></p>
      <p class="muted">작성자: <c:out value="${commit.author_login}" default="Git 계정 미확인"/> · <c:out value="${commit.reviewed_at}"/></p>
      <p class="preserve-lines"><c:out value="${commit.summary}"/></p>
      <c:if test="${commit.coverage_type == 'METADATA_ONLY'}"><p class="notice">빈 파일 생성·삭제 또는 경로·권한·파일 유형 변경의 영향을 수동 확인해 주세요. 심볼릭 링크로의 변경도 포함될 수 있습니다. 이 커밋은 별도 수정 권고 이슈를 자동 생성하지 않습니다.</p></c:if>
      <c:if test="${not empty commit.coverage_details}"><details><summary>검토 범위와 메타데이터 변경</summary><pre><c:out value="${commit.coverage_details}"/></pre></details></c:if>
    </article></c:forEach>
  </c:otherwise></c:choose>
  <nav class="pagination" aria-label="커밋 리뷰 페이지"><c:if test="${commitPage > 0}"><c:url var="previousCommitPage" value="/reviews"><c:param name="projectId" value="${projectId}"/><c:param name="commitPage" value="${commitPage - 1}"/><c:param name="runPage" value="${runPage}"/></c:url><a href="<c:out value='${previousCommitPage}'/>#commits">이전</a></c:if><span><c:out value="${commitPage + 1}"/> 페이지</span><c:if test="${hasNextCommitPage}"><c:url var="nextCommitPage" value="/reviews"><c:param name="projectId" value="${projectId}"/><c:param name="commitPage" value="${commitPage + 1}"/><c:param name="runPage" value="${runPage}"/></c:url><a href="<c:out value='${nextCommitPage}'/>#commits">다음</a></c:if></nav>
  <c:if test="${commitPage == maxHistoryPage}"><p class="muted">조회 가능한 마지막 페이지입니다.</p></c:if>
</section>
<p class="muted">AI 리뷰는 보조 의견입니다. 변경 전 코드 맥락과 테스트 결과를 확인하세요. 실패한 커밋은 성공으로 기록하거나 진행 위치를 넘기지 않습니다.</p>
<%@ include file="fragments/footer.jspf" %>
