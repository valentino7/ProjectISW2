package common.parser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import common.entity.Bug;
import common.entity.CollectBugs;
import common.entity.CollectCommits;
import common.entity.JavaFile;
import common.entity.Metrics;
import common.io.ManageDirectory;
import common.strings.Strings;

public class ParserJgit {
	private ParserJgit() {}



	public static List<Git> getRepo(String[] urlsRepos, String[] directories) {
		int i=0;
		List<Git> gits = new ArrayList<>(); 
		for (String repo : urlsRepos) {
			try {
				ManageDirectory.deleteDirectory(new File(directories[i]));
			} catch (IOException e1) {
				Logger.getLogger(ParserJgit.class.getName()).log( Level.SEVERE, e1.toString(), e1);
			}

			try {
				gits.add(Git.cloneRepository()
						.setURI(repo)
						.setDirectory(new File("repos/"+directories[i]))
						.call());
			} catch (GitAPIException e) {
				Logger.getLogger(ParserJgit.class.getName()).log( Level.SEVERE, e.toString(), e);
			}
			i++;
		}
		return gits;

	}


	public static CollectCommits getCommitsDefaultBranch(CollectBugs collectBugs, List<Bug> bugs, List<Git> gits) {
		CollectCommits collectCommits = new CollectCommits();

		for (Git git : gits) {
			Iterable<RevCommit> commits = null;
			try {
				commits = git.log().all().call();
			} catch (GitAPIException | IOException e) {
				Logger.getLogger(ParserJgit.class.getName()).log( Level.SEVERE, e.toString(), e);
			}
			for (RevCommit commit : commits) {
				for (Bug bug : bugs) {
					System.out.println(commit.getName());
					if(Pattern.compile(bug.getId()+"\\D|"+bug.getId()+"\\b").matcher(commit.getFullMessage()).find()) {
						//aggiungi commit, data committer, sha, autore 
						//commit con almeno un id uguale a quello del ticket

					}else if(Pattern.compile("BOOKKEEPER-9\\D|BOOKKEEPER-9\\b").matcher(commit.getFullMessage()).find()) {
						//aggiungi a commit other id
					}else {
						//aggiungi commit a without id
					}
				}

			}
		}

	}



	public static List<JavaFile> getChangesListsByCommit(List<Git> repos, String sha){
		List<JavaFile> lClasses = new ArrayList<>();
		Repository repository = repos.get(0).getRepository();
		RevWalk rw = new RevWalk(repository);
		ObjectId head = null;
		try {
			head = repository.resolve(sha);
		}catch( RevisionSyntaxException | IOException e) {
			Logger.getLogger(ParserJgit.class.getName()).log( Level.SEVERE, e.toString(), e);
		}
		//prendo la commit attuale
		RevCommit commit = null;
		try {
			commit = rw.parseCommit(head);
		}catch(IOException e){
			Logger.getLogger(ParserJgit.class.getName()).log( Level.SEVERE, e.toString(), e);
			rw.close();
			return lClasses;
		}
		//prendo la commit del padre
		RevCommit parent = null;
		try {
			if(commit.getParentCount()!=0) {
				parent = rw.parseCommit(commit.getParent(0).getId());
			}
		}catch(IOException e){
			Logger.getLogger(ParserJgit.class.getName()).log( Level.SEVERE, e.toString(), e);
			rw.close();
			return lClasses;
		}
		DiffFormatter df = getDiffFormatter(repository);
		List<DiffEntry> diffs = null;

		//creo diff
		try {
			if(parent == null)
				diffs = df.scan(new EmptyTreeIterator(), new CanonicalTreeParser(null, rw.getObjectReader(),commit.getTree()));
			else
				diffs = df.scan(parent.getTree(), commit.getTree());
		}catch(IOException e){
			Logger.getLogger(ParserJgit.class.getName()).log( Level.SEVERE, e.toString(), e);
		}
		//riempio la lista con le classi
		fillListWithJavaClasses(df, lClasses, diffs, commit);
		df.close();
		rw.close();
		return lClasses;
	}

	private static void fillListWithJavaClasses(DiffFormatter df, List<JavaFile> lClasses, List<DiffEntry> diffs, RevCommit commit) {
		for (DiffEntry diff : diffs) {
			String path = null;
			//se il type � delete prendo il path della commit padre
			if(diff.getChangeType().name().equals("DELETE"))
				path = diff.getOldPath();

			else
				path = diff.getNewPath();

			//processo solo i file java con un espressione regolare
			if(!Pattern.compile(Strings.REGEX_TREE_JAVA).matcher(path).find())
				continue;

			int deletedLines = 0;
			int createdLines = 0;

			try {
				for (Edit edit : df.toFileHeader(diff).toEditList()) {
					System.out.println("created B: "+ edit.getLengthB());
					System.out.println("deleted A: "+ edit.getLengthA());					
					createdLines+=edit.getLengthB();
					deletedLines+=edit.getLengthA();
				}
			} catch (IOException e) {
				Logger.getLogger(ParserJgit.class.getName()).log( Level.SEVERE, e.toString(), e);
			}

			int nfix=0;
			if(Pattern.compile("\\bfix\\b").matcher(commit.getFullMessage()).find())
				nfix=1;
			Metrics metrics	 = new Metrics(nfix);
			JavaFile javaFile = new JavaFile(path,metrics,createdLines,deletedLines,diff.getChangeType().name());
			if(diff.getChangeType().name().equals("COPY"))
				javaFile.setOldPath(diff.getOldPath());
			lClasses.add(javaFile);

		}
	}

	private static DiffFormatter getDiffFormatter(Repository repository) {
		DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
		df.setRepository(repository);
		df.setDiffComparator(RawTextComparator.DEFAULT);
		df.setContext(0);
		df.setDetectRenames(true);
		return df;
	}

}
