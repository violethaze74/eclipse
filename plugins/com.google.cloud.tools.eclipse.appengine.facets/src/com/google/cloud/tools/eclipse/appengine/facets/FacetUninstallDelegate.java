package com.google.cloud.tools.eclipse.appengine.facets;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.DefaultModelWriter;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.wst.common.project.facet.core.IDelegate;
import org.eclipse.wst.common.project.facet.core.IFacetedProject;
import org.eclipse.wst.common.project.facet.core.IProjectFacetVersion;
import org.eclipse.wst.common.project.facet.core.ProjectFacetsManager;
import org.eclipse.wst.common.project.facet.core.runtime.IRuntime;

import com.google.cloud.tools.eclipse.util.MavenUtils;
import com.google.cloud.tools.eclipse.util.status.StatusUtil;
import com.google.common.base.Preconditions;

public class FacetUninstallDelegate implements IDelegate {

  @Override
  public void execute(IProject project, IProjectFacetVersion version, Object config,
      IProgressMonitor monitor) throws CoreException {
    if (MavenUtils.hasMavenNature(project)) {
      removeAppEngineJarsFromMavenProject(project, monitor);
    } else {
      removeAppEngineJarsFromClasspath(project, monitor);
    }
    uninstallAppEngineRuntime(project, monitor);
  }

  private void removeAppEngineJarsFromClasspath(IProject project, IProgressMonitor monitor) throws CoreException {
    IJavaProject javaProject = JavaCore.create(project);
    IClasspathEntry[] rawClasspath = javaProject.getRawClasspath();
    IClasspathEntry[] newClasspath = new IClasspathEntry[rawClasspath.length - 1];

    int appEngineContainerIndex = 0;
    boolean isAppEngineSdkPresent = false;
    for (IClasspathEntry entry : rawClasspath) {
      if (AppEngineSdkClasspathContainer.CONTAINER_ID.equals(entry.getPath().toString())) {
        isAppEngineSdkPresent = true;
      } else {
        newClasspath[appEngineContainerIndex++] = entry;
      }
    }

    if(isAppEngineSdkPresent) {
      javaProject.setRawClasspath(newClasspath, monitor);
    }
  }

  /**
   * Removes all the App Engine server runtimes from the list of targeted runtimes for
   * <code>project</code>.
   */
  private void uninstallAppEngineRuntime(final IProject project, IProgressMonitor monitor) {
    Job uninstallJob = new Job("Uninstall App Engine runtimes in " + project.getName()) {

      @Override
      protected IStatus run(IProgressMonitor monitor) {
        try {
          IFacetedProject facetedProject = ProjectFacetsManager.create(project);
          Set<IRuntime> targetedRuntimes = facetedProject.getTargetedRuntimes();

          for (IRuntime targetedRuntime : targetedRuntimes) {
            if (AppEngineStandardFacet.isAppEngineStandardRuntime(targetedRuntime)) {
              facetedProject.removeTargetedRuntime(targetedRuntime, monitor);
            }
          }
          return Status.OK_STATUS;
        } catch (CoreException ex) {
          return ex.getStatus();
        }
      }
    };
    uninstallJob.schedule();

  }

  private void removeAppEngineJarsFromMavenProject(IProject project, IProgressMonitor monitor) throws CoreException {
    IMavenProjectFacade facade = MavenPlugin.getMavenProjectRegistry().getProject(project);
    Model pom = facade.getMavenProject(monitor).getModel();

    List<Dependency> currentDependencies = pom.getDependencies();
    List<Dependency> updatedDependencies = updateMavenProjectDependencies(currentDependencies);
    pom.setDependencies(updatedDependencies);

    Properties properties = pom.getProperties();
    updatePomProperties(properties, monitor);

    DefaultModelWriter writer = new DefaultModelWriter();
    File pomFile = pom.getPomFile();
    try {
      writer.write(pomFile, null /* options */, pom);
    } catch (IOException e) {
      throw new CoreException(StatusUtil.error(this, e.getMessage()));
    }
    project.getFile(pomFile.getName()).refreshLocal(IResource.DEPTH_ZERO, monitor);
  }

  //visible for testing
  public static List<Dependency> updateMavenProjectDependencies(List<Dependency> initialDependencies) {
    List<Dependency> finalDependencies = new ArrayList<Dependency>();
    if ((initialDependencies == null) || (initialDependencies.isEmpty())) {
      return finalDependencies;
    }

    List<Dependency> allAppEngineDependencies = MavenAppEngineFacetUtil.getAppEngineDependencies();
    for (Dependency dependency : initialDependencies) {
      if(!MavenUtils.doesListContainDependency(allAppEngineDependencies, dependency)) {
        finalDependencies.add(dependency);
      }
    }

    return finalDependencies;
  }

  //visible for testing
  public static void updatePomProperties(Properties projectProperties, IProgressMonitor monitor) {
    Preconditions.checkNotNull(projectProperties, "project properties is null");
    Map<String, String> allProperties = MavenAppEngineFacetUtil.getAppEnginePomProperties(monitor);
    for (Entry<String, String> property : allProperties.entrySet()) {
      if(projectProperties.containsKey(property.getKey())) {
        projectProperties.remove(property.getKey());
      }
    }
  }
}
