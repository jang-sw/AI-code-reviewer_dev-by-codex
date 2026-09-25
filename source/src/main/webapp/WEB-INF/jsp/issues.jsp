<%@ page pageEncoding="UTF-8" %>
<%@ include file="fragments/header.jspf" %>
<section class="page-heading"><p class="eyebrow">수정 권고 확인</p><h1><c:choose><c:when test="${admin}">전체 이슈</c:when><c:otherwise>내 이슈</c:otherwise></c:choose></h1><p>수정 권고를 읽고 코드와 테스트를 확인한 뒤 처리 상태를 저장하세요.</p></section>
<section class="panel"><form method="get" action="<c:url value='/issues'/>" class="toolbar"><label for="status-filter">처리 상태</label><select id="status-filter" name="status"><option value="" ${filterStatus == '' ? 'selected' : ''}>전체 상태</option><option value="OPEN" ${filterStatus == 'OPEN' ? 'selected' : ''}>미처리</option><option value="RESOLVED" ${filterStatus == 'RESOLVED' ? 'selected' : ''}>해결</option><option value="DISMISSED" ${filterStatus == 'DISMISSED' ? 'selected' : ''}>검토 제외</option></select><button type="submit">조회</button><span class="muted">최신순 · 페이지당 25건</span></form></section>
<c:choose><c:when test="${empty issues}"><section class="panel empty-state"><h2>표시할 이슈가 없습니다</h2><p><c:choose><c:when test="${page > 0}">이 페이지에는 이슈가 없습니다. 이전 페이지를 확인하세요.</c:when><c:otherwise>선택한 상태의 이슈가 없습니다. 새 리뷰에서 수정 권고가 발견되면 여기에 나타납니다.</c:otherwise></c:choose></p><a href="<c:url value='/projects'/>">프로젝트 리뷰 상태 확인</a></section></c:when><c:otherwise>
  <c:forEach items="${issues}" var="issue"><article class="panel issue-card">
    <div class="section-heading"><div><ui:status value="${issue.severity}"/> <ui:status value="${issue.status}"/></div><span class="muted">#<c:out value="${issue.id}"/> · <c:out value="${issue.project_name}"/></span></div>
    <c:url var="detailUrl" value="/issues/${issue.id}"><c:param name="filterStatus" value="${filterStatus}"/><c:param name="page" value="${page}"/></c:url>
    <h2><a href="<c:out value='${detailUrl}'/>"><c:out value="${issue.title}"/></a></h2>
    <p class="muted">담당: <c:out value="${issue.assignee_username}"/> · <code class="file-path"><c:out value="${issue.file_path}"/><c:if test="${not empty issue.line_number}">:<c:out value="${issue.line_number}"/></c:if></code></p>
    <p class="preserve-lines"><c:out value="${issue.description_preview}"/></p>
    <c:if test="${issue.assignment_reason == 'PROJECT_OWNER_FALLBACK' or issue.fallback_assignment}"><p class="hint">작성자 계정을 연결할 수 없어 프로젝트 소유자에게 배정했습니다.</p></c:if>
    <div class="actions"><a class="button button-secondary" href="<c:out value='${detailUrl}'/>">수정 권고 읽기</a><c:if test="${not empty issue.commit_url}"><a href="<c:out value='${issue.commit_url}'/>" target="_blank" rel="noopener noreferrer">원본 커밋 보기</a></c:if></div>
    <%@ include file="fragments/issue-status.jspf" %>
  </article></c:forEach>
</c:otherwise></c:choose>
<nav class="pagination" aria-label="이슈 페이지"><c:if test="${page > 0}"><c:url var="previousPage" value="/issues"><c:param name="status" value="${filterStatus}"/><c:param name="page" value="${page - 1}"/></c:url><a href="<c:out value='${previousPage}'/>">이전</a></c:if><span><c:out value="${page + 1}"/> 페이지</span><c:if test="${hasNext and page < 10000}"><c:url var="nextPage" value="/issues"><c:param name="status" value="${filterStatus}"/><c:param name="page" value="${page + 1}"/></c:url><a href="<c:out value='${nextPage}'/>">다음</a></c:if></nav>
<%@ include file="fragments/footer.jspf" %>
