<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<section class="page-heading"><p class="eyebrow">REPOSITORIES</p><h1>프로젝트</h1><p>저장소 링크 하나로 등록하세요. 관리자 승인 후 전체 이력을 시작으로 매시간 새 커밋을 리뷰합니다.</p></section>
<section class="card">
  <h2>프로젝트 등록 요청</h2>
  <form method="post" action="${pageContext.request.contextPath}/projects" class="form-grid">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
    <div class="field full-width"><label for="repositoryUrl">Git 저장소 URL</label><input id="repositoryUrl" name="repositoryUrl" type="url" required maxlength="2048" placeholder="https://github.com/team/repository.git" aria-describedby="urlHelp"></div>
    <p id="urlHelp" class="muted">GitHub 또는 허용된 GitLab 호스트의 HTTP(S) 복제 URL을 입력하세요. 비밀번호나 토큰을 URL에 넣지 마세요.</p>
    <div class="field"><label for="name">프로젝트 이름 (선택)</label><input id="name" name="name" maxlength="120" placeholder="비우면 저장소 이름 사용"></div>
    <div class="field"><label for="reviewBranch">리뷰 브랜치 (선택)</label><input id="reviewBranch" name="reviewBranch" maxlength="255" placeholder="비우면 기본 브랜치 사용"></div>
    <div><button type="submit" class="button primary">등록 요청</button></div>
  </form>
</section>
<section class="card">
  <h2><c:choose><c:when test="${isAdmin}">전체 프로젝트 · 승인 관리</c:when><c:otherwise>내 프로젝트</c:otherwise></c:choose></h2>
  <c:choose><c:when test="${empty projects}"><div class="empty-state"><h3>등록된 프로젝트가 없습니다</h3><p>위에서 Git 저장소 URL을 등록해 첫 리뷰를 준비하세요.</p></div></c:when><c:otherwise>
  <div class="table-wrap"><table><thead><tr><th>프로젝트</th><th>저장소</th><th>소유자</th><th>상태</th><th>마지막 리뷰 커밋</th></tr></thead>
    <tbody><c:forEach items="${projects}" var="project"><tr>
      <td><a href="${pageContext.request.contextPath}/projects/${project.id}"><c:out value="${project.name}"/></a></td>
      <td><span class="badge"><c:out value="${project.provider}"/></span> <c:out value="${project.repositoryPath}"/></td>
      <td><c:out value="${project.ownerUsername}"/></td>
      <td><span class="badge status-${project.status}"><c:choose><c:when test="${project.status == 'PENDING'}">승인 대기</c:when><c:when test="${project.status == 'APPROVED'}">리뷰 활성</c:when><c:when test="${project.status == 'PAUSED'}">일시 중지</c:when><c:otherwise>반려</c:otherwise></c:choose></span></td>
      <td><c:choose><c:when test="${empty project.lastReviewedSha}"><span class="muted">리뷰 대기</span></c:when><c:otherwise><code><c:out value="${project.lastReviewedSha}"/></code></c:otherwise></c:choose></td>
    </tr></c:forEach></tbody>
  </table></div></c:otherwise></c:choose>
  <nav class="pagination" aria-label="프로젝트 페이지"><c:if test="${page > 0}"><a href="${pageContext.request.contextPath}/projects?page=${page - 1}">이전</a></c:if><c:if test="${hasNext}"><a href="${pageContext.request.contextPath}/projects?page=${page + 1}">다음</a></c:if></nav>
</section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
