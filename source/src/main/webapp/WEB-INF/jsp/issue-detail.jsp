<%@ page pageEncoding="UTF-8" %>
<%@ include file="fragments/header.jspf" %>
<c:url var="listUrl" value="/issues"><c:param name="status" value="${filterStatus}"/><c:param name="page" value="${page}"/></c:url>
<p><a href="<c:out value='${listUrl}'/>">← 이슈 목록으로</a></p>
<section class="page-heading"><p class="eyebrow"><c:out value="${issue.project_name}"/> · 이슈 #<c:out value="${issue.id}"/></p><h1><c:out value="${issue.title}"/></h1><p><ui:status value="${issue.severity}"/> <ui:status value="${issue.status}"/></p></section>
<section class="panel"><h2>변경 위치와 담당자</h2><dl>
  <dt>담당자</dt><dd><c:out value="${issue.assignee_username}"/></dd>
  <dt>파일 위치</dt><dd><code class="file-path"><c:out value="${issue.file_path}"/><c:if test="${not empty issue.line_number}">:<c:out value="${issue.line_number}"/></c:if></code></dd>
  <dt>Git 작성자</dt><dd><c:out value="${issue.author_login}" default="미확인"/></dd>
  <dt>배정 근거</dt><dd><c:choose><c:when test="${issue.assignment_reason == 'GITHUB_ACCOUNT'}">GitHub 계정 연결</c:when><c:when test="${issue.assignment_reason == 'GIT_EMAIL_MAPPING'}">관리자가 연결한 Git 작성자 정보</c:when><c:when test="${issue.assignment_reason == 'PROJECT_OWNER_FALLBACK'}">프로젝트 소유자 대체 배정</c:when><c:otherwise>기존 이슈 (배정 근거 미기록)</c:otherwise></c:choose></dd>
  <dt>커밋</dt><dd><code class="commit-sha"><c:out value="${issue.commit_sha}"/></code><c:if test="${not empty issue.commit_url}"> · <a href="<c:out value='${issue.commit_url}'/>" target="_blank" rel="noopener noreferrer">원본 커밋 보기</a></c:if></dd>
</dl><c:if test="${issue.assignment_reason == 'PROJECT_OWNER_FALLBACK' or issue.fallback_assignment}"><p class="notice assignment-notice">Git 작성자와 일치하는 활성 계정이나 작성자 매핑이 없어 프로젝트 소유자에게 배정했습니다. 연결이 필요하면 관리자에게 문의하세요.</p></c:if></section>
<section class="panel"><h2>검토 내용</h2><p class="preserve-lines"><c:out value="${issue.description}"/></p><h2>수정 권고</h2><pre class="suggestion"><c:out value="${issue.suggestion}"/></pre><p class="hint">AI의 권고입니다. 실제 코드의 맥락과 테스트 결과를 확인한 뒤 반영하세요.</p></section>
<section class="panel"><h2>처리 결과 기록</h2><p>수정을 반영했다면 ‘해결’, 검토 결과 적용하지 않기로 했다면 ‘검토 제외’를 선택하세요. 다시 확인할 때는 ‘미처리’로 바꿀 수 있습니다.</p><%@ include file="fragments/issue-status.jspf" %></section>
<%@ include file="fragments/footer.jspf" %>
