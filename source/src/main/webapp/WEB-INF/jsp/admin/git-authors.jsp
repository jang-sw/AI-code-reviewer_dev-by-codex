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
  <c:if test="${not empty mappingErrors}"><div id="mapping-errors" class="notice error" role="alert"><strong>매핑을 저장하지 않았습니다.</strong><ul>
    <c:if test="${not empty mappingErrors.userId}"><li><a href="#userId"><c:out value="${mappingErrors.userId}"/></a></li></c:if>
    <c:if test="${not empty mappingErrors.repositoryOrigin}"><li><a href="#repositoryOrigin"><c:out value="${mappingErrors.repositoryOrigin}"/></a></li></c:if>
    <c:if test="${not empty mappingErrors.authorEmail}"><li><a href="#authorEmail"><c:out value="${mappingErrors.authorEmail}"/></a></li></c:if>
  </ul></div></c:if>
  <c:if test="${mappingFormCleared}"><p class="muted">자격증명이 포함될 수 있거나 길이·문자 제한을 넘은 입력은 다시 표시하지 않습니다. 서버 주소와 커밋 이메일을 다시 입력해 주세요.</p></c:if>
  <form method="get" action="${pageContext.request.contextPath}/admin/git-authors" class="filter-form">
    <input type="hidden" name="page" value="<c:out value='${page}'/>">
    <div class="field"><label for="userSearch">사용자 찾기</label><input id="userSearch" name="userSearch" maxlength="80" value="<c:out value='${userSearch}'/>" placeholder="아이디로 검색"></div>
    <div class="actions"><button type="submit" class="button secondary">검색</button></div>
  </form>
  <c:if test="${hasMoreUsers}"><p class="muted">활성 사용자 50명까지 표시합니다. 아이디 검색으로 범위를 좁혀 주세요.</p></c:if>
  <form method="post" action="${pageContext.request.contextPath}/admin/git-authors">
    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">
    <input type="hidden" name="page" value="<c:out value='${page}'/>"><input type="hidden" name="userSearch" value="<c:out value='${userSearch}'/>">
    <div class="form-grid">
    <div class="field"><label for="userId">이슈를 배정할 사용자</label><select id="userId" name="userId" required aria-invalid="${not empty mappingErrors.userId}" aria-describedby="userHelp${not empty mappingErrors.userId ? ' mapping-user-error' : ''}"><option value="">사용자 선택</option>
      <c:if test="${not empty mappingSelectedUser}"><option value="<c:out value='${mappingSelectedUser.id}'/>" selected><c:out value="${mappingSelectedUser.username}"/> (직전 선택 · 현재 활성)</option></c:if>
      <c:forEach items="${userOptions}" var="option"><c:if test="${empty mappingSelectedUser or option.id != mappingSelectedUser.id}"><option value="<c:out value='${option.id}'/>"><c:out value="${option.username}"/></option></c:if></c:forEach></select>
      <p id="userHelp" class="help muted">승인된 활성 사용자만 배정할 수 있습니다. 오류 후 직전 선택을 복원할 때도 현재 상태를 확인합니다.</p>
      <c:if test="${not empty mappingErrors.userId}"><p id="mapping-user-error" class="field-error"><c:out value="${mappingErrors.userId}"/></p></c:if>
    </div>
    <div class="field"><label for="repositoryOrigin">저장소 서버 주소</label><input id="repositoryOrigin" name="repositoryOrigin" type="url" required maxlength="512" autocomplete="off" value="<c:out value='${mappingForm.repositoryOrigin}'/>" placeholder="https://gitlab.example.com" aria-invalid="${not empty mappingErrors.repositoryOrigin}" aria-describedby="originHelp${not empty mappingErrors.repositoryOrigin ? ' mapping-origin-error' : ''}">
      <c:if test="${not empty mappingErrors.repositoryOrigin}"><p id="mapping-origin-error" class="field-error"><c:out value="${mappingErrors.repositoryOrigin}"/></p></c:if>
      <p id="originHelp" class="help muted">허용된 서버의 프로토콜, 호스트, 포트만 입력하세요. 저장소 경로는 제외합니다. HTTP와 HTTPS, 서로 다른 포트는 별개의 서버로 구분합니다.</p>
    </div>
    <div class="field"><label for="authorEmail">커밋 작성자 전체 이메일</label><input id="authorEmail" name="authorEmail" type="email" required maxlength="320" autocomplete="off" value="<c:out value='${mappingForm.authorEmail}'/>" placeholder="developer@example.com" aria-invalid="${not empty mappingErrors.authorEmail}" aria-describedby="emailHelp${not empty mappingErrors.authorEmail ? ' mapping-email-error' : ''}">
      <c:if test="${not empty mappingErrors.authorEmail}"><p id="mapping-email-error" class="field-error"><c:out value="${mappingErrors.authorEmail}"/></p></c:if>
      <p id="emailHelp" class="help muted">전체 주소를 소문자로 비교하며 +태그나 이메일 앞부분을 제거하지 않습니다. 같은 서버의 같은 이메일은 한 사용자에게만 연결할 수 있습니다.</p>
    </div>
    </div>
    <div class="actions"><button type="submit" class="button primary">매핑 등록</button></div>
  </form>
</section>
<section class="card">
  <h2>등록된 매핑</h2>
  <c:choose><c:when test="${empty mappings}"><div class="empty-state"><h3>등록된 매핑이 없습니다</h3><p>커밋 이메일을 확인하고 활성 사용자에게 연결해 주세요.</p></div></c:when><c:otherwise>
  <p class="muted table-scroll-hint" id="mappings-scroll-hint">화면이 좁으면 표에 초점을 둔 뒤 좌우 방향키로 전체 매핑과 삭제 버튼을 확인하세요.</p>
  <div class="table-wrap" tabindex="0" role="region" aria-label="등록된 Git 작성자 매핑" aria-describedby="mappings-scroll-hint"><table><thead><tr><th>저장소 서버</th><th>전체 이메일</th><th>배정 사용자</th><th>상태</th><th>관리</th></tr></thead><tbody>
    <c:forEach items="${mappings}" var="mapping"><tr>
      <td><c:out value="${mapping.repositoryOrigin}"/></td><td><c:out value="${mapping.authorEmail}"/></td><td><c:out value="${mapping.username}"/></td>
      <td><c:choose><c:when test="${mapping.userEnabled}"><span class="badge">활성</span></c:when><c:otherwise><span class="badge">계정 비활성 · 배정 중지</span></c:otherwise></c:choose></td>
      <td><form method="post" action="${pageContext.request.contextPath}/admin/git-authors/${mapping.id}/delete"><input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"><input type="hidden" name="page" value="<c:out value='${page}'/>"><input type="hidden" name="userSearch" value="<c:out value='${userSearch}'/>"><button type="submit" class="button secondary">삭제</button></form></td>
    </tr></c:forEach>
  </tbody></table></div></c:otherwise></c:choose>
  <nav class="pagination" aria-label="매핑 페이지"><c:if test="${page > 0}"><c:url var="previousPage" value="/admin/git-authors"><c:param name="page" value="${page - 1}"/><c:param name="userSearch" value="${userSearch}"/></c:url><a href="<c:out value='${previousPage}'/>">이전</a></c:if><span><c:out value="${page + 1}"/> 페이지</span><c:if test="${hasNext}"><c:url var="nextPage" value="/admin/git-authors"><c:param name="page" value="${page + 1}"/><c:param name="userSearch" value="${userSearch}"/></c:url><a href="<c:out value='${nextPage}'/>">다음</a></c:if></nav>
  <p class="muted">삭제와 재등록은 이후 새로 생성되는 이슈에 적용됩니다. 기존 이슈의 담당자는 바뀌지 않습니다.</p>
</section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
