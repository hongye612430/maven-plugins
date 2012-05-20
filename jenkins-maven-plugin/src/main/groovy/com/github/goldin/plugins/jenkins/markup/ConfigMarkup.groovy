package com.github.goldin.plugins.jenkins.markup

import com.github.goldin.plugins.jenkins.Job
import com.github.goldin.plugins.jenkins.beans.ParameterType
import org.gcontracts.annotations.Requires


/**
 * Generates Jenkins config file XML markup.
 */
class ConfigMarkup extends Markup
{
    private final Job     job
    private final String  timestamp
    private final String  indent
    private final String  newLine
    private final boolean isMavenJob


    @Requires({ job && ( timestamp != null ) && indent && newLine })
    ConfigMarkup ( Job job, String timestamp, String indent, String newLine )
    {
        super( indent, newLine )

        this.job        = job
        this.timestamp  = timestamp
        this.indent     = indent
        this.newLine    = newLine
        this.isMavenJob = Job.JobType.maven.is( job.jobType )

        /**
         * Task instances are created by Maven and need to have their
         * {@link com.github.goldin.plugins.jenkins.Task#builder} set.
         */

        assert this.builder
        job.tasks.            each { it.builder = this.builder }
        job.prebuildersTasks. each { it.builder = this.builder }
        job.postbuildersTasks.each { it.builder = this.builder }
        job.groovys.          each { it.builder = this.builder }
        if ( job.groovy ) { job.groovy.builder  = this.builder }
    }


    /**
     * Builds Jenkins config XML markup using this object markup builder.
     */
    @Override
    void addMarkup ()
    {
        builder.with {

            mkp.xmlDeclaration( version: '1.0', encoding: 'UTF-8' )

            add( '<!-- ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ -->\n' )
            add( "<!-- Generated automatically by [${ job.generationPom }]${ timestamp } -->\n" )
            add( '<!-- ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~ -->\n' )

            "${ isMavenJob ? 'maven2-moduleset' : 'project' }" {
                actions()
                addDescription()
                if ( job.displayName ){ displayName( job.displayName ) }
                if ( [ job.daysToKeep, job.numToKeep, job.artifactDaysToKeep, job.artifactNumToKeep ].any{ it > -1 } )
                {
                    logRotator {
                        daysToKeep( job.daysToKeep )
                        numToKeep( job.numToKeep )
                        artifactDaysToKeep( job.artifactDaysToKeep )
                        artifactNumToKeep( job.artifactNumToKeep )
                    }
                }
                keepDependencies( false )
                addProperties()
                addScm()
                add( 'quietPeriod',           job.quietPeriod )
                add( 'scmCheckoutRetryCount', job.scmCheckoutRetryCount )
                assignedNode( job.node ?: '' )
                canRoam( job.node ? false : true )
                disabled( job.disabled )
                blockBuildWhenDownstreamBuilding( job.blockBuildWhenDownstreamBuilding )
                blockBuildWhenUpstreamBuilding( job.blockBuildWhenUpstreamBuilding )
                jdk( job.jdkName )
                add( 'authToken', job.authToken )
                addTriggers()
                concurrentBuild( false )
                if ( isMavenJob ){ addMaven() }
                else             { builders{ job.tasks*.addMarkup() }}
                addPublishers()
                buildWrappers{ add( job.buildWrappers ) }
                if ( isMavenJob ){ addMavenBuilders() }
            }
        }
    }


    /**
     * Adds config's {@code <description>} section to the {@link #builder}.
     */
    void addDescription ()
    {
        builder.description {
            add( """
<![CDATA[<center>
    <h4>
        Job definition is generated by <a href="${ job.generationPom }">Maven</a>
        using <a href="http://evgeny-goldin.com/wiki/Jenkins-maven-plugin">&quot;jenkins-maven-plugin&quot;</a> ${ timestamp ?: '' }.
        <br/>
        If you <a href="${ job.jenkinsUrl + '/job/' + job.id + '/configure' }">configure</a> this project manually -
        it will probably be <a href="${ job.generationPom }">overwritten</a>!
    </h4>
</center>
${ job.description }
<p/>
${ new DescriptionTableMarkup( job, indent, newLine ).markup }
]]>
 ${ indent }""" ) // Indentation correction: closing </description> tag is not positioned correctly due to String content injected
        }
    }


    /**
     * Adds {@code <properties>} section to the {@link #builder}.
     */
    void addProperties()
    {
        builder.with {
            builder.properties {
                add( job.properties )
                if ( job.parameters()) {
                    'hudson.model.ParametersDefinitionProperty' {
                        parameterDefinitions {
                            job.parameters().findAll{ it.type != ParameterType.jira }*.addMarkup( builder )
                        }
                    }
                    job.parameters().findAll{ it.type == ParameterType.jira }*.addMarkup( builder )
                }
                if ( job.gitHubUrl ) { 'com.coravy.hudson.plugins.github.GithubProjectProperty' { projectUrl( job.gitHubUrl ) }}
            }
        }
    }


    /**
     * Adds {@code <scm>} section to the {@link #builder}.
     */
    void addScm()
    {
        final scmBuilderClass = job.scmMarkupBuilderClass
        if  ( scmBuilderClass )
        {
            final scm        = scmBuilderClass.newInstance()
            scm.builder      = builder
            scm.job          = job
            scm.repositories = job.repositories()
            scm.addMarkup()
        }
        add( job.scm )
    }


    /**
     * Adds {@code <triggers>} section to the {@link #builder}.
     */
    void addTriggers()
    {
        builder.with {
            triggers( class: 'vector' ) {
                for ( trigger in job.triggers())
                {
                    "${ trigger.triggerClass }" {
                        spec(( trigger.description ? "#${ trigger.description }\n" : '' ) + trigger.expression )
                    }
                }
            }
        }
    }


    /**
     * Adds {@code <triggers>} section to the {@link #builder}.
     */
    void addMaven()
    {
        assert isMavenJob

        builder.with {

            rootPOM( job.pom )
            goals( job.mavenGoals )
            mavenName( job.mavenName )
            mavenOpts( job.mavenOpts ?: '' )
            aggregatorStyleBuild( true )
            incrementalBuild( job.incrementalBuild )

            if ( job.privateRepository || job.privateRepositoryPerExecutor )
            {
                localRepository( class: "hudson.maven.local_repo.${ job.privateRepository ? 'PerJobLocalRepositoryLocator' : 'PerExecutorLocalRepositoryLocator' }" )
            }

            ignoreUpstremChanges( ! job.buildOnSNAPSHOT )
            archivingDisabled( job.archivingDisabled )
            resolveDependencies( false )
            processPlugins( false )
            mavenValidationLevel( 0 )
            runHeadless( false )

            reporters {
                add( job.reporters )
                if ( job.mail.recipients )
                {
                    'hudson.maven.reporters.MavenMailer' {
                        recipients( job.mail.recipients )
                        dontNotifyEveryUnstableBuild( ! job.mail.sendForUnstable )
                        sendToIndividuals( job.mail.sendToIndividuals )
                    }
                }
            }
        }
    }


    /**
     * Adds Maven {@code <prebuilders>} and {@code <postbuilders>} to the {@link #builder}.
     */
    void addMavenBuilders()
    {
        assert isMavenJob

        builder.with {
            prebuilders {
                add( job.prebuilders )
                job.groovys().findAll{ it.pre }*.addMarkup()
                job.prebuildersTasks*.addMarkup()
            }

            postbuilders {
                add( job.postbuilders )
                job.groovys().findAll{ ! it.pre }*.addMarkup()
                job.postbuildersTasks*.addMarkup()
            }

            runPostStepsIfResult {
                name   ( job.runPostStepsIfResult.name    )
                ordinal( job.runPostStepsIfResult.ordinal )
                color  ( job.runPostStepsIfResult.color   )
            }
        }
    }


    /**
     * Adds {@code <publishers>} section to the {@link #builder}.
     */
    void addPublishers()
    {
        add( job.publishers )

        builder.with {
            if (( ! isMavenJob ) && ( job.mail.recipients ))
            {
                'hudson.tasks.Mailer' {
                    recipients( job.mail.recipients )
                    dontNotifyEveryUnstableBuild( ! job.mail.sendForUnstable )
                    sendToIndividuals( job.mail.sendToIndividuals )
                }
            }

            if (( isMavenJob ) && ( job.deploy.url ))
            {
                'hudson.maven.RedeployPublisher' {
                    id( job.deploy.id )
                    url( job.deploy.url )
                    uniqueVersion( job.deploy.uniqueVersion )
                    evenIfUnstable( job.deploy.evenIfUnstable )
                }
            }

            if (( isMavenJob ) && ( job.artifactory.name ))
            {
                'org.jfrog.hudson.ArtifactoryRedeployPublisher' {
                    details {
                        artifactoryName( job.artifactory.name )
                        repositoryKey( job.artifactory.repository )
                        snapshotsRepositoryKey( job.artifactory.snapshotsRepository )
                    }
                    deployArtifacts( job.artifactory.deployArtifacts )
                    username( job.artifactory.user )
                    scrambledPassword( job.artifactory.scrambledPassword )
                    includeEnvVars( job.artifactory.includeEnvVars )
                    skipBuildInfoDeploy( job.artifactory.skipBuildInfoDeploy )
                    evenIfUnstable( job.artifactory.evenIfUnstable )
                    runChecks( job.artifactory.runChecks )
                    violationRecipients( job.artifactory.violationRecipients )
                }
            }

            if ( job.invoke.jobs )
            {
                'hudson.plugins.parameterizedtrigger.BuildTrigger' {
                    configs {
                        'hudson.plugins.parameterizedtrigger.BuildTriggerConfig' {

                            final anyConfigs = ( job.invoke.currentBuildParams || job.invoke.subversionRevisionParam ||
                                                 job.invoke.gitCommitParam     || job.invoke.params                  ||
                                                 job.invoke.propertiesFileParams )

                            if ( ! anyConfigs )
                            {
                                configs( class: 'java.util.Collections$EmptyList' )
                            }
                            else
                            {
                                configs {
                                    if ( job.invoke.currentBuildParams      ){ 'hudson.plugins.parameterizedtrigger.CurrentBuildParameters'()}
                                    if ( job.invoke.subversionRevisionParam ){ 'hudson.plugins.parameterizedtrigger.SubversionRevisionBuildParameters'()}
                                    if ( job.invoke.gitCommitParam          ){ 'hudson.plugins.git.GitRevisionBuildParameters'()}

                                    if ( job.invoke.params )
                                    {
                                        'hudson.plugins.parameterizedtrigger.PredefinedBuildParameters' {
                                            builder.properties( job.invoke.params.readLines()*.trim().join( '\n' ))
                                        }
                                    }

                                    if ( job.invoke.propertiesFileParams )
                                    {
                                        'hudson.plugins.parameterizedtrigger.FileBuildParameters' {
                                            propertiesFile( job.invoke.propertiesFileParams )
                                        }
                                    }
                                }
                            }

                            projects( job.invoke.jobs )
                            condition( job.invoke.condition[ 0 ] )
                            triggerWithNoParameters( job.invoke.triggerWithoutParameters )
                        }
                    }
                }
            }
        }
    }
}
