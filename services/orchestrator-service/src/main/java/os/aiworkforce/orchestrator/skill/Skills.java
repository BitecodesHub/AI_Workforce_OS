// @find: skills repository, find skills, skill by id in workspace, skill name taken, mark workspace seeded, skill_seeds, Skills
// @what: Spring Data repository for Skill rows, plus the race-safe marker that a workspace has been given the ready-made skills.
// @flow: Used by SkillService
package os.aiworkforce.orchestrator.skill;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/*
 * Spring Data scans for top-level repository interfaces only, so this sits in its own file like
 * every other repository in this service.
 */

public interface Skills extends JpaRepository<Skill, UUID> {

    List<Skill> findByOrgId(UUID orgId);

    Optional<Skill> findByIdAndOrgId(UUID id, UUID orgId);

    /** Whether a skill of this name exists in the workspace, ignoring case, as the unique index does. */
    // @find: skill name taken, duplicate skill name
    @Query("select count(s) > 0 from Skill s where s.orgId = :orgId and lower(s.name) = lower(:name)")
    boolean nameTaken(@Param("orgId") UUID orgId, @Param("name") String name);

    /** The same, leaving out one skill, so renaming a skill to its own name in another case is allowed. */
    @Query("select count(s) > 0 from Skill s where s.orgId = :orgId and lower(s.name) = lower(:name) and s.id <> :id")
    boolean nameTakenByAnother(@Param("orgId") UUID orgId, @Param("name") String name, @Param("id") UUID id);

    /**
     * Marks a workspace as given the ready-made skills, once.
     *
     * <p>Returns 1 for the one caller that wrote the marker and 0 for every other, including one
     * racing it: the second insert waits on the first transaction and then finds the row, so only
     * one request ever seeds.
     */
    // @find: mark workspace seeded with skills, skill_seeds insert
    @Modifying
    @Query(
            value = "insert into skill_seeds (org_id, seeded_at) values (:orgId, now()) on conflict (org_id) do nothing",
            nativeQuery = true)
    int markSeeded(@Param("orgId") UUID orgId);
}
