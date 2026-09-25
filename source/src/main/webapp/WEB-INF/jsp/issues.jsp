<%@ page pageEncoding="UTF-8" %>
<%@ include file="fragments/header.jspf" %>
<section class="page-heading"><p class="eyebrow">ISSUE INBOX</p><h1><c:choose><c:when test="${admin}">전체 내부 이슈함</c:when><c:otherwise>내 내부 이슈함</c:otherwise></c:choose></h1><p>AI가 제안한 수정 권고를 확인하고 처리 상태를 관리합니다.</p></section>
<section class="panel"><form method="get" action="<c:url value='/issues'/>" class="toolbar"><label for="status-filter">상태</label><select id="status-filter" name="status"><option value="" ${filterStatus == '' ? 'selected' : ''}>전체</option><option value="OPEN" ${filterStatus == 'OPEN' ? 'selected' : ''}>열림</option><option value="RESOLVED" ${filterStatus == 'RESOLVED' ? 'selected' : ''}>해결</option><option value="DISMISSED" ${filterStatus == 'DISMISSED' ? 'selected' : ''}>제외</option></select><button type="submit">조회</button><span>총 <c:out value="${total}"/>건</span></form></section>
<c:choose><c:when test="${empty issues}"><section class="panel empty-state"><h2>표시할 이슈가 없습니다</h2><p>현재 상태에 해당하는 이슈가 없습니다. 새 리뷰에서 수정 권고가 발견되면 여기에 나타납니다.</p></section></c:when><c:otherwise>
  <c:forEach items="${issues}" var="issue"><article class="panel issue-card">
    <div class="section-heading"><div><span class="badge"><c:out value="${issue.severity}"/></span> <span class="badge"><c:out value="${issue.status}"/></span></div><span class="muted">#<c:out value="${issue.id}"/> · <c:out value="${issue.project_name}"/></span></div>
    <h2><c:out value="${issue.title}"/></h2>
    <p class="muted">담당: <c:out value="${issue.assignee_username}"/> · Git 작성자: <c:out value="${issue.author_login}" default="미확인"/></p>
    <p class="muted">배정 근거: <c:choose><c:when test="${issue.assignment_reason == 'GITHUB_ACCOUNT'}">GitHub 계정 연결</c:when><c:when test="${issue.assignment_reason == 'GIT_EMAIL_MAPPING'}">관리자가 등록한 저장소 이메일 매핑</c:when><c:when test="${issue.assignment_reason == 'PROJECT_OWNER_FALLBACK'}">프로젝트 소유자 대체 배정</c:when><c:otherwise>기존 이슈 (배정 근거 미기록)</c:otherwise></c:choose></p>
    <c:if test="${issue.assignment_reason == 'PROJECT_OWNER_FALLBACK' or issue.fallback_assignment}"><p class="notice">Git 작성자와 일치하는 활성 계정이나 저장소 이메일 매핑이 없어 프로젝트 소유자에게 배정된 이슈입니다.</p></c:if>
    <p><code class="commit-sha"><c:out value="${issue.commit_sha}"/></code></p>
    <c:if test="${not empty issue.commit_url}"><p><a href="<c:out value='${issue.commit_url}'/>" target="_blank" rel="noopener noreferrer">원본 커밋 보기</a></p></c:if>
    <p><code class="file-path"><c:out value="${issue.file_path}"/><c:if test="${not empty issue.line_number}">:<c:out value="${issue.line_number}"/></c:if></code></p>
    <h3>검토 내용</h3><p class="preserve-lines"><c:out value="${issue.description}"/></p>
    <h3>수정 권고</h3><pre class="suggestion"><c:out value="${issue.suggestion}"/></pre>
    <c:url var="statusAction" value="/issues/${issue.id}/status"/><form method="post" action="<c:out value='${statusAction}'/>" class="toolbar"><input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><label for="issue-status-${issue.id}">처리 상태</label><select id="issue-status-${issue.id}" name="status"><option value="OPEN" ${issue.status == 'OPEN' ? 'selected' : ''}>열림</option><option value="RESOLVED" ${issue.status == 'RESOLVED' ? 'selected' : ''}>해결</option><option value="DISMISSED" ${issue.status == 'DISMISSED' ? 'selected' : ''}>제외</option></select><button type="submit">상태 저장</button></form>
  </article></c:forEach>
</c:otherwise></c:choose>
<nav class="pagination" aria-label="이슈 페이지"><c:if test="${page > 0}"><c:url var="previousPage" value="/issues"><c:param name="status" value="${filterStatus}"/><c:param name="page" value="${page - 1}"/></c:url><a href="<c:out value='${previousPage}'/>">이전</a></c:if><span><c:out value="${page + 1}"/> 페이지</span><c:if test="${hasNext}"><c:url var="nextPage" value="/issues"><c:param name="status" value="${filterStatus}"/><c:param name="page" value="${page + 1}"/></c:url><a href="<c:out value='${nextPage}'/>">다음</a></c:if></nav>
<%@ include file="fragments/footer.jspf" %>
