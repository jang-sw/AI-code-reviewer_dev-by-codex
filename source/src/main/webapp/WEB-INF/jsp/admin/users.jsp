<%@ page pageEncoding="UTF-8" %>
<%@ include file="/WEB-INF/jsp/fragments/header.jspf" %>
<section class="page-heading"><p class="eyebrow">ADMINISTRATION</p><h1>사용자 가입 승인</h1><p>사용자가 직접 가입한 요청을 검토하세요. 승인 전에는 로그인하거나 프로젝트를 등록할 수 없습니다.</p></section>
<section class="card"><h2>승인 요청과 계정 조회</h2>
  <c:url var="usersUrl" value="/admin/users"/>
  <form method="get" action="<c:out value='${usersUrl}'/>" class="form-grid">
    <div class="field"><label for="status">승인 상태</label><select id="status" name="status">
      <option value="" ${approvalFilter == '' ? 'selected' : ''}>전체</option>
      <option value="PENDING" ${approvalFilter == 'PENDING' ? 'selected' : ''}>승인 대기</option>
      <option value="APPROVED" ${approvalFilter == 'APPROVED' ? 'selected' : ''}>승인 완료</option>
      <option value="REJECTED" ${approvalFilter == 'REJECTED' ? 'selected' : ''}>반려</option>
    </select></div>
    <div class="field"><label for="search">아이디 검색</label><input id="search" name="search" maxlength="80" value="<c:out value='${search}'/>"></div>
    <div><button type="submit">조회</button></div>
  </form>
</section>
<c:if test="${not empty gitCorrectionTarget}">
<section class="card" aria-labelledby="git-correction-title">
  <h2 id="git-correction-title">Git 사용자명 정정</h2>
  <p role="alert" class="form-error"><a href="#git-correction-value"><c:out value="${gitCorrectionError}"/></a></p>
  <p>대상 아이디: <strong><c:out value="${gitCorrectionTarget.username}"/></strong> · 현재 Git 사용자명: <strong><c:out value="${gitCorrectionTarget.gitUsername}"/></strong></p>
  <c:url var="gitCorrectionAction" value="/admin/users/${gitCorrectionTarget.id}/git-username"/>
  <form method="post" action="<c:out value='${gitCorrectionAction}'/>" class="form-stack">
    <input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>">
    <input type="hidden" name="expectedGitUsername" value="<c:out value='${gitCorrectionTarget.gitUsername}'/>">
    <input type="hidden" name="status" value="<c:out value='${approvalFilter}'/>"><input type="hidden" name="search" value="<c:out value='${search}'/>"><input type="hidden" name="page" value="<c:out value='${page}'/>">
    <label for="git-correction-value">새 Git 사용자명</label><input id="git-correction-value" name="newGitUsername" required maxlength="100" autocomplete="off" aria-invalid="true" aria-describedby="git-correction-help" value="<c:out value='${gitCorrectionValue}'/>">
    <p id="git-correction-help" class="muted">토큰·비밀번호를 입력하지 마세요. 안전한 사용자명 형태의 입력만 복원합니다. 기존 이슈의 담당자는 유지하며, 정정 후 새 작성자 매칭 조회부터 반영합니다. 이미 매칭 중인 리뷰는 이전 배정으로 저장될 수 있습니다.</p>
    <button type="submit">Git 사용자명 저장</button>
  </form>
</section>
</c:if>
<section class="card"><h2>사용자 목록</h2><p class="muted">최신순 · 페이지당 50명. 승인 상태와 이용 상태는 별도로 관리합니다.</p>
  <c:choose><c:when test="${empty users}"><div class="empty-state"><c:choose><c:when test="${page > 0}">
    <h3>이 페이지에 표시할 사용자가 없습니다</h3><p>목록이 변경되었거나 마지막 페이지를 넘었습니다. 현재 승인 상태와 검색 조건을 유지한 채 첫 페이지나 이전 페이지에서 확인해 주세요.</p>
    <c:url var="firstUserPage" value="/admin/users"><c:param name="page" value="0"/><c:param name="status" value="${approvalFilter}"/><c:param name="search" value="${search}"/></c:url><a id="users-first-page" href="<c:out value='${firstUserPage}'/>">첫 페이지로</a>
  </c:when><c:otherwise><p>조회 조건에 맞는 사용자가 없습니다.</p></c:otherwise></c:choose></div></c:when><c:otherwise>
  <p class="muted table-scroll-hint" id="users-scroll-hint">화면이 좁으면 사용자 목록을 좌우로 스크롤해 모든 관리 기능을 확인하세요.</p>
  <div class="table-wrap" tabindex="0" role="region" aria-label="사용자 승인 및 계정 관리 목록" aria-describedby="users-scroll-hint"><table class="admin-users-table"><thead><tr><th>아이디 / Git 계정</th><th>권한</th><th>승인 상태</th><th>이용 상태</th><th>승인 처리</th><th>계정 관리</th></tr></thead><tbody>
  <c:forEach items="${users}" var="user"><tr>
    <td><strong><c:out value="${user.username}"/></strong><br><c:out value="${user.gitUsername}"/></td>
    <td><c:choose><c:when test="${user.admin}">관리자</c:when><c:otherwise>일반 사용자</c:otherwise></c:choose></td>
    <td><span class="badge"><c:choose><c:when test="${user.approvalStatus == 'PENDING'}">승인 대기</c:when><c:when test="${user.approved}">승인 완료</c:when><c:otherwise>반려</c:otherwise></c:choose></span></td>
    <td><c:choose><c:when test="${not user.approved}">승인 후 이용 가능</c:when><c:when test="${user.enabled}">이용 중</c:when><c:otherwise>이용 중지</c:otherwise></c:choose></td>
    <td><c:choose><c:when test="${user.approvalStatus == 'PENDING'}">
      <c:url var="approveAction" value="/admin/users/${user.id}/approve"/>
      <form method="post" action="<c:out value='${approveAction}'/>"><input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><input type="hidden" name="status" value="<c:out value='${approvalFilter}'/>"><input type="hidden" name="search" value="<c:out value='${search}'/>"><input type="hidden" name="page" value="<c:out value='${page}'/>"><button type="submit">가입 승인</button></form>
      <details><summary>반려</summary><c:url var="rejectAction" value="/admin/users/${user.id}/reject"/>
      <form method="post" action="<c:out value='${rejectAction}'/>" class="form-stack"><input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><input type="hidden" name="status" value="<c:out value='${approvalFilter}'/>"><input type="hidden" name="search" value="<c:out value='${search}'/>"><input type="hidden" name="page" value="<c:out value='${page}'/>">
        <label for="reason-${user.id}">반려 사유 (선택)</label><input id="reason-${user.id}" name="reason" maxlength="500"><button type="submit" class="button-secondary">가입 반려</button>
      </form></details>
    </c:when><c:when test="${user.approvalStatus == 'REJECTED'}">
      <p><strong>반려 사유</strong><br><c:choose><c:when test="${not empty user.approvalReason}"><c:out value="${user.approvalReason}"/></c:when><c:otherwise><span class="muted">입력된 사유가 없습니다.</span></c:otherwise></c:choose></p>
      <p class="muted">승인 대기로 되돌려도 사유는 감사 기록에 남습니다.</p>
      <c:url var="reopenAction" value="/admin/users/${user.id}/reopen"/><form method="post" action="<c:out value='${reopenAction}'/>"><input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><input type="hidden" name="status" value="<c:out value='${approvalFilter}'/>"><input type="hidden" name="search" value="<c:out value='${search}'/>"><input type="hidden" name="page" value="<c:out value='${page}'/>"><button type="submit" class="button-secondary">승인 대기로 되돌리기</button></form>
    </c:when><c:otherwise>처리 완료</c:otherwise></c:choose></td>
    <td><c:if test="${user.approved}">
      <c:url var="enabledAction" value="/admin/users/${user.id}/enabled"/><form method="post" action="<c:out value='${enabledAction}'/>"><input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><input type="hidden" name="status" value="<c:out value='${approvalFilter}'/>"><input type="hidden" name="search" value="<c:out value='${search}'/>"><input type="hidden" name="page" value="<c:out value='${page}'/>"><input type="hidden" name="enabled" value="${not user.enabled}"><button type="submit" class="button-secondary"><c:choose><c:when test="${user.enabled}">이용 중지</c:when><c:otherwise>이용 재개</c:otherwise></c:choose></button></form>
      <details><summary>비밀번호 초기화</summary><c:url var="passwordAction" value="/admin/users/${user.id}/password"/>
      <form method="post" action="<c:out value='${passwordAction}'/>" class="form-stack"><input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><input type="hidden" name="status" value="<c:out value='${approvalFilter}'/>"><input type="hidden" name="search" value="<c:out value='${search}'/>"><input type="hidden" name="page" value="<c:out value='${page}'/>"><label for="reset-${user.id}">새 비밀번호</label><input id="reset-${user.id}" name="newPassword" type="password" required minlength="12" autocomplete="new-password"><p class="muted">이 계정의 기존 로그인 세션을 만료합니다.</p><button type="submit" class="button-secondary">비밀번호 초기화</button></form></details>
    </c:if>
      <details><summary>Git 사용자명 정정</summary><c:url var="gitAction" value="/admin/users/${user.id}/git-username"/>
      <form method="post" action="<c:out value='${gitAction}'/>" class="form-stack">
        <input type="hidden" name="<c:out value='${_csrf.parameterName}'/>" value="<c:out value='${_csrf.token}'/>"><input type="hidden" name="expectedGitUsername" value="<c:out value='${user.gitUsername}'/>">
        <input type="hidden" name="status" value="<c:out value='${approvalFilter}'/>"><input type="hidden" name="search" value="<c:out value='${search}'/>"><input type="hidden" name="page" value="<c:out value='${page}'/>">
        <label for="git-${user.id}">새 Git 사용자명</label><input id="git-${user.id}" name="newGitUsername" required maxlength="100" autocomplete="off" aria-describedby="git-help-${user.id}">
        <p id="git-help-${user.id}" class="muted">토큰·비밀번호가 아닌 사용자명을 입력하세요. 승인 상태와 기존 이슈 담당자는 유지됩니다. 정정 후 새 작성자 매칭 조회부터 반영하며 이미 매칭 중인 리뷰는 이전 배정으로 저장될 수 있습니다.</p>
        <button type="submit" class="button-secondary">Git 사용자명 저장</button>
      </form></details>
    </td>
  </tr></c:forEach></tbody></table></div></c:otherwise></c:choose>
  <nav class="pagination" aria-label="사용자 페이지">
    <c:if test="${page > 0}"><c:url var="previousPage" value="/admin/users"><c:param name="page" value="${page - 1}"/><c:param name="status" value="${approvalFilter}"/><c:param name="search" value="${search}"/></c:url><a href="<c:out value='${previousPage}'/>">이전</a></c:if>
    <span><c:out value="${page + 1}"/> 페이지</span>
    <c:if test="${hasNext}"><c:url var="nextPage" value="/admin/users"><c:param name="page" value="${page + 1}"/><c:param name="status" value="${approvalFilter}"/><c:param name="search" value="${search}"/></c:url><a href="<c:out value='${nextPage}'/>">다음</a></c:if>
  </nav>
</section>
<%@ include file="/WEB-INF/jsp/fragments/footer.jspf" %>
