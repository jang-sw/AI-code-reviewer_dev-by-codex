<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<section class="page-heading"><p class="eyebrow">AUTHOR ASSIGNMENT</p><h1>Git 작성자 매핑</h1><p>저장소 서버와 커밋 이메일을 사용자 계정에 연결해 리뷰 이슈를 배정합니다.</p></section>
<section class="card">
  <h2>배정 기준</h2>
  <p>GitHub 계정명이 등록된 활성 사용자와 일치하면 먼저 해당 사용자에게 배정합니다. 그 외에는 서버 주소와 전체 이메일이 일치하는 매핑을 사용하고, 활성 매핑이 없으면 프로젝트 소유자에게 배정합니다.</p>
  <p class="muted">커밋 이메일은 작성자가 직접 기록한 정보이며 본인 인증 결과가 아닙니다. 매핑은 이슈 배정에만 사용하며 계정 권한이나 프로젝트 접근 권한을 부여하지 않습니다. 이메일은 이 관리자 화면에서만 표시합니다.</p>
</section>
<section class="card">
  <h2>매핑 등록</h2>
  <form method="get" action="${pageContext.request.contextPath}/admin/git-authors" class="actions">
    <label for="userSearch">사용자 찾기</label><input id="userSearch" name="userSearch" maxlength="80" value="<c:out value='${userSearch}'/>" placeholder="아이디로 검색">
    <button type="submit" class="button secondary">검색</button>
  </form>
  <c:if test="${hasMoreUsers}"><p class="muted">활성 사용자 50명까지 표시합니다. 아이디 검색으로 범위를 좁혀 주세요.</p></c:if>
  <form method="post" action="${pageContext.request.contextPath}/admin/git-authors" class="form-grid">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
    <div class="field"><label for="userId">이슈를 배정할 사용자</label><select id="userId" name="userId" required><option value="">사용자 선택</option><c:forEach items="${userOptions}" var="option"><option value="${option.id}"><c:out value="${option.username}"/></option></c:forEach></select></div>
    <div class="field"><label for="repositoryOrigin">저장소 서버 주소</label><input id="repositoryOrigin" name="repositoryOrigin" type="url" required maxlength="512" placeholder="https://gitlab.example.com" aria-describedby="originHelp"></div>
    <p id="originHelp" class="muted">허용된 서버의 프로토콜, 호스트, 포트만 입력하세요. 저장소 경로는 제외합니다. HTTP와 HTTPS, 서로 다른 포트는 별개의 서버로 구분합니다.</p>
    <div class="field full-width"><label for="authorEmail">커밋 작성자 전체 이메일</label><input id="authorEmail" name="authorEmail" type="email" required maxlength="320" autocomplete="off" placeholder="developer@example.com" aria-describedby="emailHelp"></div>
    <p id="emailHelp" class="muted">전체 주소를 소문자로 비교하며 +태그나 이메일 앞부분을 제거하지 않습니다. 같은 서버의 같은 이메일은 한 사용자에게만 연결할 수 있습니다.</p>
    <div><button type="submit" class="button primary">매핑 등록</button></div>
  </form>
</section>
<section class="card">
  <h2>등록된 매핑</h2>
  <c:choose><c:when test="${empty mappings}"><div class="empty-state"><h3>등록된 매핑이 없습니다</h3><p>커밋 이메일을 확인하고 활성 사용자에게 연결해 주세요.</p></div></c:when><c:otherwise>
  <div class="table-wrap"><table><thead><tr><th>저장소 서버</th><th>전체 이메일</th><th>배정 사용자</th><th>상태</th><th>관리</th></tr></thead><tbody>
    <c:forEach items="${mappings}" var="mapping"><tr>
      <td><c:out value="${mapping.repositoryOrigin}"/></td><td><c:out value="${mapping.authorEmail}"/></td><td><c:out value="${mapping.username}"/></td>
      <td><c:choose><c:when test="${mapping.userEnabled}"><span class="badge">활성</span></c:when><c:otherwise><span class="badge">계정 비활성 · 배정 중지</span></c:otherwise></c:choose></td>
      <td><form method="post" action="${pageContext.request.contextPath}/admin/git-authors/${mapping.id}/delete"><input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"><button type="submit" class="button secondary">삭제</button></form></td>
    </tr></c:forEach>
  </tbody></table></div></c:otherwise></c:choose>
  <nav class="pagination" aria-label="매핑 페이지"><c:if test="${page > 0}"><a href="${pageContext.request.contextPath}/admin/git-authors?page=${page - 1}">이전</a></c:if><c:if test="${hasNext}"><a href="${pageContext.request.contextPath}/admin/git-authors?page=${page + 1}">다음</a></c:if></nav>
  <p class="muted">삭제와 재등록은 이후 새로 생성되는 이슈에 적용됩니다. 기존 이슈의 담당자는 바뀌지 않습니다.</p>
</section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
